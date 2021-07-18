import com.typesafe.scalalogging.Logger

import java.io.{FileInputStream, FileOutputStream, ObjectInputStream, ObjectOutputStream}
import java.nio.file.{Files, Paths}
import java.rmi.Remote
import java.rmi.registry.{LocateRegistry, Registry}
import java.rmi.server.UnicastRemoteObject
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Random, Success, Try}
import scala.collection.mutable

final case class Block(blockUUID: UUID, dataNodes: List[String])

trait MasterService extends Remote {
  @throws[java.rmi.RemoteException]
  def write(path: String, size: Long): List[Block]

  @throws[java.rmi.RemoteException]
  def read(filename: String): Option[List[Block]]

  @throws[java.rmi.RemoteException]
  def remove(filename: String): Unit

  @throws[java.rmi.RemoteException]
  def listDataNodeNames: List[String]

  @throws[java.rmi.RemoteException]
  def getBlockSize: Int

  @throws[java.rmi.RemoteException]
  def exportFileTable(): Map[String, List[Block]]

  @throws[java.rmi.RemoteException]
  def sayHelloFromServer(): String

  @throws[java.rmi.RemoteException]
  def registerNodes(nodeName: String, node: Remote): Unit

  @throws[java.rmi.RemoteException]
  def listRegisteredDataNodes: Map[String, Remote]
}

sealed class MasterNode extends UnicastRemoteObject with MasterService {
  private val logger = Logger("MasterNode")
  private val BlockSize: Int = 64
  private val ReplicationFactor: Int = 3
  private var fileTable: Map[String, List[Block]] = Map()
  private val DumpDestination: String = "data/masterdump.bin"

  private val nodes: mutable.Map[String, Remote] = mutable.Map()
  protected val masterHost: String = "localhost"

  if (System.getSecurityManager == null) System.setSecurityManager(new SecurityManager)

  MasterNode.rebindMasterNodeToMasterRegistry(this, masterHost)
  // todo: does not work - because of docker, need to fix
  suckUpDump()
  onShutDown()

  override def sayHelloFromServer(): String = {
    logger.info("Say Hello invoked...")
    "Hello from MasterNode!"
  }

  override def registerNodes(nodeName: String, node: Remote): Unit = {
    logger.info(s"Registering data node: ${nodeName} ...")
    nodes += (nodeName -> node)
    nodes.foreach(node => logger.info(node.toString))
  }

  override def listRegisteredDataNodes: Map[String, Remote] = {
    nodes.toMap
  }

  override def write(path: String, size: Long): List[Block] = {
    val blockCount: Long = calculateBlockCount(size)
    val blocks = allocateBlocks(blockCount)
    fileTable += (path -> blocks)
    blocks
  }

  override def read(filename: String): Option[List[Block]] = {
    logger.info(s"reading file: $filename")
    fileTable.get(filename)
  }

  override def remove(filename: String): Unit = {
    fileTable -= filename
  }

  override def listDataNodeNames: List[String] = {
    logger.info("requesting list of data nodes...")
    nodes.keys.toList
  }

  override def getBlockSize: Int = BlockSize

  override def exportFileTable(): Map[String, List[Block]] = fileTable

  private def calculateBlockCount(size: Long): Long = math.ceil(size / BlockSize).toLong

  private def allocateBlocks(blockCount: Long): List[Block] = {
    val blocks: ListBuffer[Block] = ListBuffer()
    val dataNodes = listDataNodeNames

    (0L to blockCount).foreach { _ =>
      val shuffledDataNodes = Random.shuffle(dataNodes).take(ReplicationFactor)
      val block = Block(UUID.randomUUID(), shuffledDataNodes)
      blocks += block
    }
    blocks.toList
  }

  private def dumpFileTable(): Unit = {
    val dest = Paths.get(DumpDestination)
    Files.createDirectories(dest.getParent)
    val fo = new FileOutputStream(dest.toFile)
    val oo = new ObjectOutputStream(fo)
    oo.writeObject(fileTable)
    oo.close()
    fo.close()
  }

  private def onShutDown(): Unit = {
    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run(): Unit = {
        logger.info("Shutdown hook ran")
        logger.info("File table dump...")
        dumpFileTable()
      }
    })
  }

  private def suckUpDump(): Unit = {
    val dest = Paths.get(DumpDestination)
    if (Files.exists(dest)) {
      logger.info("Reading binary file table dump...")
      val fi = new FileInputStream(dest.toFile)
      val oi = new ObjectInputStream(fi)
      fileTable = oi.readObject() match {
        case data: Map[String, List[Block]] => data
        case _ => Map()
      }
    }
  }
}

object MasterNode {
  private val logger = Logger("MasterNode")
  private val masterRegistry: Registry = launchMasterRegistry()

  def apply(): MasterService = {
    new MasterNode
  }

  private def rebindMasterNodeToMasterRegistry(service: MasterService, masterHost: String): Unit = {
    Try(masterRegistry.rebind(s"rmi://${masterHost}:1099/MasterNode", service)) match {
      case Success(_) => logger.info("MasterNode registered at Master RMI registry...")
      case Failure(ex) => ex.printStackTrace()
    }
  }

  private def launchMasterRegistry(): Registry = {
    logger.info("Master RMI registry starting...")
    LocateRegistry.createRegistry(1099) match {
      case registry: Registry => registry
      case _ => println("Could not create Master registry!")
        sys.exit(0)
    }
  }

  def main(args: Array[String]): Unit = {
    MasterNode()
  }
}

