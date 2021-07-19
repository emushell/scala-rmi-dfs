# Simplified Distributed-File-System in Scala

DFS implemented purely in Scala using Java RMI
==============================================
An implementation attempt of simple distributed file system like HDFS (as a basis google DFS) for self learning purpose.
The basis consists of MasterNode, a DataNode(s), and a Client who sparks magic in the whole system. The MasterNode
should dump the whole namespace into a binary object each time when the master node goes down and picks it up at start.
Data replication should happen the same way as HDFS does, but of course in a much simpler way. But the main idea is the
same - data replication happens from one data node to another as far as it goes. Reading files/data happens in the same
manner. First we ask the first node for the stored block, if it ignores you (crashes/down/fails) then proceed with next
data node and so on. As a basis for the implementation Java RMI is used as a communication channel between MasterNode,
DataNode(s) and Client.

### Prerequisites:

- Scala 2.13.6
- Sbt 1.5.5
- Docker
- docker-compose

### Getting started:

1. clone git repo:

```shell
git clone
```

2. compile the project - in the project root dir execute:

```sbt
sbt assembly
```

3. start the docker images (wait until some images will be downloaded):

```sh
docker-compose up -d
```

4. and - voilà, your simple "neighborhood dfs" is up and running. To start using the dfs, launch client which interacts
   with it - by executing:

```shell
scala client/target/scala-2.13/client_2.13.6-0.1.0-SNAPSHOT.jar
```

### Commands:

There is a set of commands which are implemented in the system. Not much but for basic operations should be sufficient:

- ```put <source-file> <destination-file>```
- ```get <filename-on-dfs>``` - command copies file back to your working directory
- ```cat <filename-on-dfs>``` - returns the content of file on dfs
- ```rm <filename-on-dfs>``` - delete file and all replicated blocks
- ```listFiles``` - returns whole list of files on dfs
- ```listNodes``` - returns a list of nodes as a string representations
- ```exit | quit``` - stops/exits the client

### Thing to take notice / remember:

1. As the dfs system runs in docker containers, and the nodes physically are not separate machines. You might end up
   in networking issues. There might be a necessity to make seme tweaks in your hosts file (on Ubuntu ```/etc/hosts```),
   something like this:

```shell
127.0.0.1 scala-rmi-master scala-rmi-node1 scala-rmi-node2 scala-rmi-node3
```

or

```shell
0.0.0.0   scala-rmi-master scala-rmi-node1 scala-rmi-node2 scala-rmi-node3
```

2. Configuration part - such as block size, replication factor etc. Is hardcoded. If there is an interest to play a
   round a bit more, please be free to change the code yourself and recompile the project.

### TODO:
1. finish config
2. consider all the rmi registry stuff take out as separate functionality
3. would be nice the to use better approach node allocation for putting the blocks. As now, it is randomly. Could be also use wight how big is each node.
4. fix issue with namespace and docker
5. can think about some tree-like structure for namespace. (would be fancier)
6. fix bugs and perhaps implement some new features...

### Some thoughts and problems which I encountered during development:

1. If you are using or considering using Java RMI, and you do not know what kind of "animal" that is, be prepared to
   have a lot less sleep as you would anticipate. If for some reason you are using some classes which are not in the
   same module you need to deal with ```codebase```. So basically it means you need set a proper jvm
   option ```-Djava.rmi.server.codebase```. Some useful explanation I found
   [here](https://stackoverflow.com/questions/464687/running-rmi-server-classnotfound) in A. Levy answer.

2. Try to avoid using scala ```Vector[_]``` type of any shape between rmi node calls. You could end up in some really
   weird exceptions with scala reflections such as ```"scala.reflect.classtag.cache.disable"```. For the sake of your
   mental health and time just convert to some more convenient data type for Java.
3. As I mentioned already before - if there is some code which is used in your module but happens to be in another
   module, do all the necessary setup for rmi codebase and its policies.
4. I came across a problem when compiling a project with different versions of scala 2.12 and 2.13. It seemed to me
   between these two versions there is a slight difference in how scala ```object```'s are instantiated. Particularly I
   came across this problem when I was trying to extending UnicastRemoteObject with and scala ```object``` and after
   that in a constructor binds that object to rmi registry. So once again - for the sake of your mental health, just
   separate those responsibilities into separate objects. But overall this could also be just my lack of knowledge about
   scala or Java RMI.
5. Keep in mind that with Java RMI you cannot bind your remote nodes to some master RMI registry which is on a separate
   machine. Java RMI just does not work like that. You can bind only apps which are on the same host. So it means for
   each node you have to have its own separate RMI registry and keep additional registry object in the master node which
   gives you information about all the other nodes. Exception which you could get if ignoring this, could look
   something like this: ```java.rmi.AccessException: Registry.rebind disallowed; origin /172.30.0.3 is non-local host```
6. Some useful resources which I found for my self during the project
- [accesscontrolexception access denied](https://stackoverflow.com/questions/2427473/java-rmi-accesscontrolexception-access-denied)
- [codebase](https://docs.oracle.com/javase/7/docs/technotes/guides/rmi/codebase.html)
- [tutorial rmi](https://docs.oracle.com/javase/tutorial/rmi/running.html)
- [security manager](https://newbedev.com/java-no-security-manager-rmi-class-loader-disabled)
  and [this](https://stackoverflow.com/questions/6322107/java-no-security-manager-rmi-class-loader-disabled)
  
