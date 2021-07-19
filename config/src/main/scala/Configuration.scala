import com.typesafe.scalalogging.Logger
import pureconfig._
import pureconfig.generic.auto._

import java.nio.file.{Path, Paths}


case class BlockSize(size: Int) extends AnyVal

case class ReplicationFactor(value: Int) extends AnyVal

case class MasterRmiHost(host: String) extends AnyVal
case class MasterRmiPort(port: Int) extends AnyVal
case class NodeRmiHost(host: String) extends AnyVal
case class NodeRmiPort(port: Int) extends AnyVal
case class MasterHost(host: String) extends AnyVal

case class DumpDestination(path: Path) extends AnyVal

case class DFSConf(blockSize: BlockSize,
                   replicationFactor: ReplicationFactor,
                   masterRmiHost: MasterRmiHost,
                   masterRmiPort: MasterRmiPort,
                   nodeRmiHost: NodeRmiHost,
                   nodeRmiPort: NodeRmiPort,
                   masterHost: MasterHost,
                   dumpDestination: DumpDestination
                  )

object Configuration {
  private val logger = Logger("Configuration")
  logger.info("Loading cluster configuration...")
  val defaultDfsConfig: ConfigObjectSource = ConfigSource.default
  val appDfsConfig: ConfigObjectSource = ConfigSource.file(Paths.get("conf/application.conf"))
  val dfsConfig: DFSConf = appDfsConfig.withFallback(defaultDfsConfig).load[DFSConf] match {
    case Right(conf) => conf
    case Left(value) => logger.error("configuration errors:")
      value.toList.foreach(er => logger.error(er.toString))
      sys.exit(0)
  }
  logger.info(dfsConfig.toString)
}
