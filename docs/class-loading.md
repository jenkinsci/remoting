# Class loading

Remoting features a flexible remote procedure call (RPC) interface.
`hudson.remoting.Channel#call{Async}` executes a `Callable` on the receiving side and returns its result to the sending side.
Serialization and deserialization are largely transparent to the user,
with Remoting leaning heavily and often implicitly on native Java object serialization.
The request class is serialized on the sending side and deserialized on the receiving side,
necessitating a remote class loading scheme for arbitrary classes on the receiving side.
This is in stark contrast to other systems like gRPC,
which typically require the developer to explicitly define the the request and response messages using an interface definition language (IDL) like Protocol Buffers.

The original design of Remoting did not assume any particular application,
although Jenkins-specific logic has gradually crept in over the years.
As such, Remoting's class loading scheme is generic and not tied to any particular class loader hierarchy.
For example, Jenkins uses a sophisticated [class loader hierarchy](https://www.jenkins.io/doc/developer/plugin-development/dependencies-and-class-loading/) on the sending (i.e., controller) side in which Jenkins core delegates to the Java Platform and in which Jenkins plugins delegate to Jenkins core and to their dependencies.
Remoting does keep track of which class loader loaded a given class,
but it has no knowledge of the relationships between class loaders.

With Remoting, the class is loaded on the sending side as usual, including complex class loading delegation, without any Remoting-specific logic.
The class loader is then exported by Remoting on the sending side,
and a thin proxy class loader is created on the receiving side.
This proxy class loader passes through any class loading requests to the sending side via RPCs.

In the simplest form of this scheme,
the proxy class loader on the receiving side makes an RPC to load a particular class and then receives its byte code from the sending side.
This results in many inefficient round trips to load each class,
leading to the implementation of caching and prefetching optimizations.

### Caching

The caching optimization allows the receiving side to request (or the sending side to proactively send) an entire checksummed JAR file rather than an individual class's bytecode,
which is then cached to disk on the receiving side.
Subsequent class loading operations of classes from the same JAR only need to transfer class metadata (like class loader and JAR file checksum), not class data (like JAR contents).
Since the cache persists across invocations on the same system (e.g., on a static agent),
subsequent invocations may not need to transfer any class data from the sending side to the receiving side if the cache is warm;
however, they will still need to transfer class metadata as in the simple scheme.

Note that while a JAR file is being transferred from the sending side to the receiving side,
class loading operations do not block on the JAR file transfer.
Rather, they fall back to the original simple scheme of individual RPCs to transfer each class's bytecode, one class at a time.
Only after the entire JAR file has been transferred will the receiving side stop requesting the bytecode for individual classes.
Since transferring the JAR file takes a small amount of time,
one typically observes a small burst of RPCs to load individual classes before the caching system kicks in and starts loading classes from the local JAR file.
If, for some reason, the JAR file cannot be transferred successfully (e.g., if it is too large and the available bandwidth is insufficient),
then the original simple scheme of individual RPCs will remain in effect.

### Prefetching

The prefetching optimization is a classic cache prefetching scheme designed to reduce the number of round trips for class metadata and data.
When responding to a request for a given class,
the sending side also examines that class's bytecode to find the class's direct dependencies and includes them in the response as well.
The reasoning here is that the client is likely to request these classes anyway,
so by including them proactively the sending side can save the receiving side another request.
The cost of this scheme is potentially loading unnecessary classes into memory.

This is particularly relevant in the context of the caching feature described previously
because we usually send the whole JAR file (i.e., the class data) to the receiving side anyway.
Sending more class metadata in the response enables more classes from that JAR to be used without another trip to the sending side.
Note that having the JAR file in the cache is not enough to allow the class to be loaded on the receiving side.
An RPC must still be made to the sending side in order to load the class if necessary (including complex delegation logic);
the only benefit of the cached JAR file is that the RPC's response need not waste any bandwidth by including class data.

## Future work

The Remoting class loading scheme features a low barrier to entry for developers.
However, this comes at the cost of a rather complex implicit serialization and deserialization scheme.
This scheme is optimized for traditional Jenkins use cases that focused heavily on local area networks and static agents,
where frequent RPCs were not an issue and where persistent local disk caches could be long-lived.
These optimizations are not as beneficial in cloud environments,
where agents are often ephemeral and can be distributed across wide area networks, sometimes across cloud providers.

Future work on Remoting would benefit from focusing on these two problems.
For example, it would be desirable to find a way to make the cache effective on ephemeral agents.
Doing more prefetching could reduce latency at the cost of increasing memory consumption.

At its core, the latency problem in the existing implementation is caused by the use of thin proxy class loaders on the receiving side
that do all the heavy lifting through RPCs to their original counterparts on the sending side,
including the implementation of complex delegation logic.
It would be desirable to find an encoding of the class loader hierarchy that could be efficiently transmitted to the receiving side
in order for the receiving side to be able to load classes from the JAR cache without constant RPCs to the sending side.
Solving the latency problem in this manner would be a major rewrite of this subsystem,
especially without loss of the generality that exists in the codebase today.
