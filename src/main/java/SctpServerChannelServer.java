import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpMultiChannel;
import com.sun.nio.sctp.SctpServerChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;

public class SctpServerChannelServer {

    private final InetSocketAddress address;
    private final ServerSocketEventListener listener;
    private SctpServerChannel serverSocketChannel;
    private Selector selector;
    private SelectionKey selectionKey;

    public SctpServerChannelServer(InetSocketAddress address, ServerSocketEventListener listener) {
        this.address = address;
        this.listener = listener;
    }

    
    protected void init() throws Exception {
        connect(address);
    }

    
    protected void service() throws Exception {
        listen(selector);
    }

    
    protected void destroy() throws Exception {
        System.out.println("closing connections");  
        selectionKey.cancel();
        selector.close();
        serverSocketChannel.close();
        selector = null;
        serverSocketChannel = null;
    }

    private void connect(InetSocketAddress address) throws IOException {
        serverSocketChannel = SctpServerChannel.open();
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.bind(address);
        System.out.println(serverSocketChannel.validOps());
        selector = Selector.open();
        selectionKey = serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        System.out.println("server listening at " + address);
    }

    private void listen(Selector selector) throws IOException {
        while (!Thread.interrupted() && selector.isOpen()) {
            int count = selector.select(500);
            if (count == 0) {
                continue;
            }

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                final SelectionKey key = it.next();
                it.remove();
                if (!key.isValid()) {
                    continue;
                }
                if (key.isAcceptable()) {
                    accept(key, selector);
                    continue;
                }
                if (key.isReadable()) {
                    try {
                        listener.read(key);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    continue;
                }
                if (key.isWritable()) {
                    try {
                        listener.write(key);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    continue;
                }
            }
        }
    }

    private void accept(SelectionKey key, Selector selector) throws IOException {
        SctpServerChannel serverSocketChannel = (SctpServerChannel) key.channel();
        SctpChannel client = serverSocketChannel.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        System.out.println("client connected ...");
    }

    public InetSocketAddress getAddress() {
        return address;
    }

}
