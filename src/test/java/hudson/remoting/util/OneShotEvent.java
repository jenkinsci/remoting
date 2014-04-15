package hudson.remoting.util;

public final class OneShotEvent {
    private boolean signaled;
    private final Object lock;

    public OneShotEvent() {
        this.lock = this;
    }

    public OneShotEvent(Object lock) {
        this.lock = lock;
    }

    /**
     * Non-blocking method that signals this event.
     */
    public void signal() {
        synchronized (lock) {
            if(signaled)        return;
            this.signaled = true;
            lock.notifyAll();
        }
    }

    /**
     * Blocks until the event becomes the signaled state.
     *
     * <p>
     * This method blocks infinitely until a value is offered.
     */
    public void block() throws InterruptedException {
        synchronized (lock) {
            while(!signaled)
                lock.wait();
        }
    }

    /**
     * Blocks until the event becomes the signaled state.
     *
     * <p>
     * If the specified amount of time elapses,
     * this method returns null even if the value isn't offered.
     */
    public void block(long timeout) throws InterruptedException {
        synchronized (lock) {
            if(!signaled)
                lock.wait(timeout);
        }
    }

    /**
     * Returns true if a value is offered.
     */
    public boolean isSignaled() {
        synchronized (lock) {
            return signaled;
        }
    }
}
