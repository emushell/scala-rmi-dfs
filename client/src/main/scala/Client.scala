import com.typesafe.scalalogging.Logger

import java.io.{BufferedInputStream, BufferedOutputStream, FileInputStream, FileOutputStream}
import java.nio.file.{Files, Path, Paths}
import java.rmi.Remote
import java.rmi.registry.{LocateRegistry, Registry}
import java.util.Scanner
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

sealed trait Command

case object Put extends Command

case object Get extends Command

case object Remove extends Command

case object Cat extends Command

case object Exit extends Command

case object ListFiles extends Command

case object Unknown extends Command

case object ListNodes extends Command

object Command {
  def apply(name: String): Command = {
    name match {
      case "put" => Put
      case "get" => Get
      case "rm" => Remove
      case "cat" => Cat
      case "exit" | "quit" => Exit
      case "listFiles" => ListFiles
      case "listNodes" => ListNodes
      case _ => Unknown
    }
  }
}

object Client {
  private val logger = Logger("Client")
  private val conf = Configuration.dfsConfig
  private val mastersNode: MasterService = lookupMasterNode()

  private def getMasterRegistry: Registry = {
    logger.info(s"Reaching out to MasterNode master RMI registry: ${conf.masterHost.host}:${conf.masterRmi.port}")
    Try(LocateRegistry.getRegistry(conf.masterHost.host, conf.masterRmi.port)) match {
      case Success(registry) => registry
      case Failure(ex) => logger.error("Master registry could not be found", ex)
        sys.exit(0)
    }
  }

  private def lookupMasterNode(): MasterService = {
    val masterRegistry = getMasterRegistry
    val rmiUrl = s"rmi://${conf.masterRmi.host}:${conf.masterRmi.port}/MasterNode"
    logger.info(s"Looking up MasterNode at: $rmiUrl")
    Try(masterRegistry.lookup(rmiUrl)) match {
      case Success(service: MasterService) => service
      case Failure(ex) => logger.error(s"MasterNode could not be found in Master RMI registry at: $rmiUrl", ex)
        sys.exit(0)
    }
  }

  private def listDataNodes(): Unit = {
    mastersNode.listRegisteredDataNodes.foreach { node =>
      val newNode = node._2.asInstanceOf[DataService]

      Try(newNode.sayHelloFromNode()) match {
        case Success(message) => logger.info(message + " - " + node.toString)
        case Failure(ex) => logger.warn(s"Node: ${node._1} cannot be reached..., node probably down...", ex)
      }
    }
  }

  private def listFiles(): Unit = {
    mastersNode.exportFileTable().foreach { file =>
      logger.info(s"File: ${file._1}")
      logger.info("blocks: ")
      file._2.foreach(block => logger.info(block.toString))
    }
  }

  private def put(source: Path, dest: String): Unit = {
    if (!Files.exists(source)) {
      logger.info(s"no such file: ${source.toString}")
      return
    }

    val fileSize = Files.size(source)
    val blockSize = mastersNode.getBlockSize
    val blocks = mastersNode.write(dest, fileSize)
    val dataNodes: Map[String, Remote] = mastersNode.listRegisteredDataNodes

    logger.info(s"put file: ${source.toString}, file size: $fileSize bytes")

    val in = new FileInputStream(source.toFile)
    val bufferedInputStream = new BufferedInputStream(in)

    val buffer: Array[Byte] = new Array[Byte](blockSize)
    var read = 0

    blocks.foreach { block =>
      if (read != -1) {
        read = bufferedInputStream.read(buffer, 0, buffer.length)
        dataNodes.get(block.dataNodes.head) match {
          case Some(dataNode: DataService) => dataNode.put(block.blockUUID.toString, buffer.slice(0, read), block.dataNodes.tail)
          case None => logger.warn(s"Could not locate data node: ${block.dataNodes.head}")
        }
      }
    }
    bufferedInputStream.close()
  }

  def get(filename: String): Unit = {
    val blockSize = mastersNode.getBlockSize
    val dataNodes: Map[String, Remote] = mastersNode.listRegisteredDataNodes
    val blocks = mastersNode.read(filename) match {
      case Some(fileBlocks) => fileBlocks
      case None =>
        logger.warn("File blocks not found. Possibly a corrupt file")
        Nil
    }

    val os = new FileOutputStream(filename)
    val bufferedOutputStream = new BufferedOutputStream(os, blockSize)

    // if node - fails recursively proceed to next node(replica) to read the data
    @tailrec
    def readBlock(nodeName: String, blockUUID: String, nodes: List[String]): Unit = {
      dataNodes.get(nodeName) match {
        case None => readBlock(nodes.head, blockUUID, nodes.tail)
        case Some(node: DataService) => Try(node.get(blockUUID)) match {
          case Success(byteArray) =>
            bufferedOutputStream.write(byteArray)
            bufferedOutputStream.flush()
          case Failure(ex) =>
            logger.error(s"Cannot reach node: $nodeName..., node down...", ex)
            if (nodes.isEmpty) return
            readBlock(nodes.head, blockUUID, nodes.tail)
        }
      }
    }

    blocks.foreach(block => readBlock(block.dataNodes.head, block.blockUUID.toString, block.dataNodes.tail))
    bufferedOutputStream.close()
  }

  def remove(filename: String): Unit = {
    val dataNodes: Map[String, Remote] = mastersNode.listRegisteredDataNodes
    val blocks = mastersNode.read(filename) match {
      case Some(fileBlocks) => fileBlocks
      case None =>
        logger.warn("File blocks not found. Possibly a corrupt file")
        Nil
    }

    @tailrec
    def deleteBlock(nodeName: String, blockUUID: String, nodes: List[String]): Unit = {
      dataNodes.get(nodeName) match {
        case None => deleteBlock(nodes.head, blockUUID, nodes.tail)
        case Some(node: DataService) => Try(node.remove(blockUUID, nodes)) match {
          case Success(result) => if (result) {
            logger.info(s"block: $blockUUID deleted...")
          } else {
            logger.info(s"for some reason block: $blockUUID could not be deleted...")
          }
          case Failure(ex) =>
            logger.error(s"Cannot reach node: $nodeName..., node down...", ex)
            if (nodes.isEmpty) return
            deleteBlock(nodes.head, blockUUID, nodes.tail)
        }
      }
    }

    blocks.foreach(block => deleteBlock(block.dataNodes.head, block.blockUUID.toString, block.dataNodes.tail))
    mastersNode.remove(filename)
  }

  def cat(filename: String): Unit = {
    val dataNodes: Map[String, Remote] = mastersNode.listRegisteredDataNodes
    val blocks = mastersNode.read(filename) match {
      case Some(fileBlocks) => fileBlocks
      case None =>
        logger.warn("File blocks not found. Possibly a corrupt file")
        Nil
    }

    // if node fails - recursively proceed to next node(replica) to read the data
    @tailrec
    def readBlock(nodeName: String, blockUUID: String, nodes: List[String]): Unit = {
      dataNodes.get(nodeName) match {
        case None => readBlock(nodes.head, blockUUID, nodes.tail)
        case Some(node: DataService) => Try(node.get(blockUUID).map(_.toChar).mkString) match {
          case Success(string) => print(string)
          case Failure(ex) =>
            // ex.printStackTrace()
            if (nodes.isEmpty) return
            readBlock(nodes.head, blockUUID, nodes.tail)
        }
      }
    }

    blocks.foreach(block => readBlock(block.dataNodes.head, block.blockUUID.toString, block.dataNodes.tail))
  }

  def main(args: Array[String]): Unit = {

    println("Enter your command: ")
    var break = true
    while (break) {
      val sc = new Scanner(System.in)
      Command(sc.next()) match {
        case Put => put(Paths.get(sc.next()), sc.next())
        case Get => get(sc.next())
        case Remove => remove(sc.next())
        case Cat => cat(sc.next())
        case Exit => break = false
        case ListFiles => listFiles()
        case ListNodes => listDataNodes()
        case Unknown => println("command not recognized... try one more time...")
          println("available commands: put | get | rm | cat | exit | listFiles | listNodes")
      }
    }
  }
}
