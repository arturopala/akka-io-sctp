import java.nio.channels.SelectionKey;

/**
 * ServerSocketEventListener is an interface complementing {@link MultiplexedServerSocket}
 */
public interface ServerSocketEventListener {
    /**
     * Read from the socket channel represented by the SelectionKey
     *
     * @throws Exception
     */
    void read(SelectionKey key) throws Exception;

    /**
     * Write to the socket channel represented by the SelectionKey
     *
     * @throws Exception
     */
    void write(SelectionKey key) throws Exception;
}