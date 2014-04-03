# Shutdown sequence of a Channel
A `Channel` builds on top of two uni-directional stream of bytes that can be independently closed, like a TCP socket.

## Orderly shutdown
The orderly shutdown of a channel goes through a sequence somewhat like
[a termination of a TCP socket](http://en.wikipedia.org/wiki/Transmission_Control_Protocol#Connection_termination).

The shutdown sequence starts by the `Channel.close()` call, which sends out a `CloseCommand` (equivalent of TCP FIN.)
The receiver marks that, sends out its own `CloseCommand` in response. The initiator receives and executes this,
successfully closing down both directions of the streams.

A `CloseCommand` is always the last command on a stream.  This sender uses `outClosed` field to track if it has
already sent out the `CloseCommand`. When it sends out the command, the field is set to non-null. Likewise,
the receiver uses `inClosed` field to track whether it has received a `CloseCommand`.


## Unorderly shutdown
A termination can be also initiated by the reader side noticing that the sender no longer exists.
This depends on the ability of the underlying transport to detect that, which can take long time
to detect this situation (for example, it is possible to kill one end of a TCP socket in such
a way that the other side will block forever.)

Anyway, when the reader end determines that the sender is gone (such as EOF of a socket), it immediately
gets to the `Channel.terminate()` method, which declares both directions of the streams dead, and
generally behaves as if it has received a `CloseCommand`, except that it doesn't send one out since
the transport is assumed to be dead.

(TODO: now that I'm writing about it, I feel like it should try to send out `CloseCommand`, because
one side noticing that the connection is bad doesn't mean the other side notices it right away.)