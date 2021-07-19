import com.typesafe.scalalogging.Logger

import java.nio.file.{Files, Path, Paths}
import java.rmi.registry.{LocateRegistry, Registry}
import java.rmi.Remote
import java.rmi.server.UnicastRemoteObject
import scala.util.{Failure, Success, Try}

trait DataService extends Remote {
  @throws[java.rmi.RemoteException]
  def put(blockUUID: String, data: Array[Byte], dataNodes: List[String]): Unit

  @throws[java.rmi.RemoteException]
  def get(blockUUID: String): Array[Byte]

  @throws[java.rmi.RemoteException]
  def sayHelloFromNode(): String

  @throws[java.rmi.RemoteException]
  def remove(blockUUID: String, dataNodes: List[String]): Boolean
}

sealed class DataNode(port: Int, nodeName: String) extends UnicastRemoteObject(port) with DataService {
  protected val logger: Logger = Logger("DataNode")
  logger.info("Starting data node...")

  protected val Dir: Path = Paths.get(s"data/$nodeName/")
  protected val masterNode: MasterService = DataNode.lookupMasterNode

  logger.info(masterNode.sayHelloFromServer())
  DataNode.rebindDataNodeToNodeRegistry(this, nodeName)
  DataNode.registerNodeToMaster(nodeName)
  // todo: does not work - because of docker, need to fix
  DataNode.removeNodeFromRegistry(nodeName)

  override def sayHelloFromNode(): String = {
    logger.info("Say Hello invoked...")
    s"Hello from $nodeName!"
  }

  override def get(blockUUID: String): Array[Byte] = {
    logger.info(s"reading block: $blockUUID from file system")
    val filePath = Dir.resolve(Paths.get(blockUUID))
    Files.readAllBytes(filePath)
  }

  override def put(blockUUID: String, data: Array[Byte], dataNodes: List[String]): Unit = {
    logger.info(s"writing block: $blockUUID to files system...")
    val filePath = Dir.resolve(Paths.get(blockUUID))
    if (!Files.exists(filePath.getParent)) Files.createDirectories(filePath.getParent)
    Files.write(filePath, data)

    getNextDataNode(dataNodes) match {
      case Some(dataNode) => dataNode.put(blockUUID, data, dataNodes.tail)
      case None => logger.info("No node to put forward...")
    }
  }

  override def remove(blockUUID: String, dataNodes: List[String]): Boolean = {
    logger.info(s"deleting block: $blockUUID from files system...")
    Files.deleteIfExists(Dir.resolve(Paths.get(blockUUID)))

    getNextDataNode(dataNodes) match {
      case Some(dataNode) => dataNode.remove(blockUUID, dataNodes.tail)
      case None =>
        logger.info("No node to put forward...")
        true
    }
  }

  private def getNextDataNode(dataNodes: List[String]): Option[DataService] = dataNodes match {
    case head :: _ => masterNode.listRegisteredDataNodes.get(head) match {
      case Some(node: DataService) => Some(node)
      case _ =>
        logger.warn(s"could not find the node $head...")
        None
    }
    case Nil => None
  }
}

object DataNode {
  private val logger: Logger = Logger("DataNode")
  private val conf = Configuration.dfsConfig
  private val nodeRegistry: Registry = createNodeRegistry

  def apply(): DataService = apply("DataNode")

  def apply(nodeName: String): DataService = apply(10991, nodeName)

  def apply(port: Int, nodeName: String): DataService = new DataNode(port, nodeName)

  private def getMasterRegistry: Registry = {
    LocateRegistry.getRegistry(conf.masterHost.host, conf.masterRmiPort.port) match {
      case registry: Registry => registry
      case _ => logger.warn("Master registry could not be found!")
        sys.exit(0)
    }
  }

  private def lookupMasterNode: MasterService = {
    val masterRegistry = getMasterRegistry
    val rmiUri = s"rmi://${conf.masterRmiHost.host}:${conf.masterRmiPort.port}/MasterNode"
    Try(masterRegistry.lookup(rmiUri)) match {
      case Success(masterNode: MasterService) => masterNode
      case Failure(ex) => logger.warn("Could not lookup MasterNode in master RMI..")
        ex.printStackTrace()
        sys.exit(0)
    }
  }

  private def createNodeRegistry: Registry = {
    LocateRegistry.createRegistry(conf.nodeRmiPort.port)
  }

  private def rebindDataNodeToNodeRegistry(service: DataService, nodeName: String): Unit = {
    val rmiUrl = s"rmi://${conf.nodeRmiHost.host}:${conf.nodeRmiPort.port}/$nodeName"
    logger.info(s"Node registry url: $rmiUrl")
    Try(nodeRegistry.rebind(rmiUrl, service)) match {
      case Success(_) => logger.info(s"$nodeName added to data node RMI registry...")
      case Failure(ex) => ex.printStackTrace()
    }
  }

  private def registerNodeToMaster(nodeName: String): Unit = {
    val masterNode = lookupMasterNode
    val rmiUrl = s"rmi://${conf.nodeRmiHost.host}:${conf.nodeRmiPort.port}/$nodeName"
    val node = nodeRegistry.lookup(rmiUrl)
    masterNode.registerNodes(nodeName, node)
  }

  private def removeNodeFromRegistry(nodeName: String): Unit = {
    val rmiUrl = s"rmi://${conf.nodeRmiHost.host}:${conf.nodeRmiPort.port}/$nodeName"
    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run(): Unit = {
        logger.info(s"Remove data node: $nodeName from node registry...")
        nodeRegistry.unbind(rmiUrl)
      }
    })
  }

  def main(args: Array[String]): Unit = {
    val nodeData = args match {
      case Array(nodeName, nodePort, _*) => (nodeName, nodePort.toInt)
      case _ => logger.warn("Name or Port is not provided, data node will shutdown it self immediate...")
        args.foreach(value => logger.warn(value))
        sys.exit(0)
    }
    DataNode(nodeData._2, nodeData._1)
  }
}