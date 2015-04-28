import com.sun.nio.sctp.*;
import com.sun.nio.sctp.AssociationChangeNotification.AssocChangeEvent;

import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

public class App {
	
	public static void main(String args[]) throws Exception {
		InetSocketAddress address = new InetSocketAddress("127.0.0.1",8000);
		ServerSocketEventListener listener = new MyServerSocketEventListener();
		final SctpServerChannelServer server = new SctpServerChannelServer(address,listener);

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try{
                server.destroy();
            }
            catch(Exception e){}
        }));

		server.init();
		server.service();
		
		Thread.currentThread().sleep(0);
	}

}

class MyServerSocketEventListener implements ServerSocketEventListener {

	ByteBuffer buf = ByteBuffer.allocateDirect(1024);

	@Override
    public void read(SelectionKey key) throws Exception {
		SctpChannel channel = (SctpChannel) key.channel();
		AssociationHandler assocHandler = new AssociationHandler(key);
		MessageInfo messageInfo = channel.receive(buf, System.out, assocHandler);
		if(messageInfo!=null && messageInfo.isComplete()) {
			buf.flip();
			System.out.println(messageInfo + "\n" + buf);
			buf.clear();
		}
	}

    @Override
    public void write(SelectionKey key) throws Exception {
		/*SctpMultiChannel channel = (SctpMultiChannel) key.channel();
        CharsetEncoder encoder = charset.newEncoder();
        StringBuffer stringBuffer = new StringBuffer("Hello World!");
        ByteBuffer buffer = encoder.encode(CharBuffer.wrap(stringBuffer));
		MessageInfo messageInfo = MessageInfo.createOutgoing(null, 1);
        if (channel.isOpen()) {
            channel.send(buffer, messageInfo);
        }*/
		key.interestOps(SelectionKey.OP_READ);
		System.out.println("ready to read");
		key.cancel();
	}
}

class AssociationHandler extends AbstractNotificationHandler<PrintStream>
{
	private SelectionKey key;

	public AssociationHandler(SelectionKey key) {
		this.key = key;
	}

	public HandlerResult handleNotification(AssociationChangeNotification not,
											PrintStream stream) {
		if (not.event().equals(AssocChangeEvent.COMM_UP)) {
			int outbound = not.association().maxOutboundStreams();
			int inbound = not.association().maxInboundStreams();
			stream.printf("New association setup with %d outbound streams" +
					", and %d inbound streams.\n", outbound, inbound);
		}

		return HandlerResult.CONTINUE;
	}

	public HandlerResult handleNotification(ShutdownNotification not,
											PrintStream stream) {
		stream.printf("The association has been shutdown.\n");
		key.cancel();
		return HandlerResult.RETURN;
	}

	public HandlerResult handleNotification(PeerAddressChangeNotification not, PrintStream stream) {
		stream.printf("The peer address has changed "+not+".\n");
		return HandlerResult.CONTINUE;
	}

	public HandlerResult handleNotification(SendFailedNotification not, PrintStream stream) {
		stream.printf("Send failed "+not+".\n");
		return HandlerResult.CONTINUE;
	}

}