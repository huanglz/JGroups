package org.jgroups.protocols;

import org.jgroups.Global;
import org.jgroups.annotations.*;
import org.jgroups.stack.Protocol;
import org.jgroups.util.Util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Protocol which provides STOMP support. Very simple implementation, with a 1 thread / connection model. Use for
 * a few hundred clients max.
 * @author Bela Ban
 * @version $Id: STOMP.java,v 1.2 2010/10/21 13:12:25 belaban Exp $
 * @since 2.11
 */
@MBean
@Experimental @Unsupported
public class STOMP extends Protocol implements Runnable {

    /* -----------------------------------------    Properties     ----------------------------------------------- */
    @Property(description="Port on which the STOMP protocol listens for requests",writable=false)
    protected int port=8787;


    /* ---------------------------------------------   JMX      ---------------------------------------------------*/
    @ManagedAttribute(description="Number of client connections",writable=false)
    int getNumConnections() {return connections.size();}


    /* --------------------------------------------- Fields ------------------------------------------------------ */
    protected ServerSocket           srv_sock;
    protected Thread                 acceptor;
    protected final List<Connection> connections=new LinkedList<Connection>();




    
    public STOMP() {
    }



    public void start() throws Exception {
        super.start();
        srv_sock=Util.createServerSocket(getSocketFactory(), Global.STOMP_SRV_SOCK, port);
        if(log.isDebugEnabled())
            log.debug("server socket listening on " + srv_sock.getLocalSocketAddress());

        if(acceptor == null) {
            acceptor=getThreadFactory().newThread(this, "STOMP acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }
    }


    public void stop() {
        if(log.isDebugEnabled())
            log.debug("closing server socket " + srv_sock.getLocalSocketAddress());

        if(acceptor != null && acceptor.isAlive()) {
            try {
                // this will terminate thread, peer will receive SocketException (socket close)
                getSocketFactory().close(srv_sock);
            }
            catch(Exception ex) {
            }
        }
        synchronized(connections) {
            for(Connection conn: connections) {
                conn.stop();
            }
            connections.clear();
        }
        acceptor=null;
        super.stop();
    }

    // Acceptor loop
    public void run() {
        Socket client_sock;
        while(acceptor != null && srv_sock != null) {
            try {
                if(log.isTraceEnabled()) // +++ remove
                    log.trace("waiting for client connections on " + srv_sock.getInetAddress() + ":" +
                            srv_sock.getLocalPort());
                client_sock=srv_sock.accept();
                if(log.isTraceEnabled()) // +++ remove
                    log.trace("accepted connection from " + client_sock.getInetAddress() + ':' + client_sock.getPort());
                Connection conn=new Connection(client_sock);
                Thread thread=getThreadFactory().newThread(conn, "STOMP client connection");
                thread.setDaemon(true);

                synchronized(connections) {
                    connections.add(conn);
                }
                thread.start();
            }
            catch(IOException io_ex) {
                break;
            }
        }
        acceptor=null;
    }


    /**
     * Class which handles a connection to a client
     */
    protected class Connection implements Runnable {
        protected final Socket sock;
        protected final DataInputStream in;
        protected final DataOutputStream out;

        public Connection(Socket sock) throws IOException {
            this.sock=sock;
            this.in=new DataInputStream(sock.getInputStream());
            this.out=new DataOutputStream(sock.getOutputStream());
        }

        public void stop() {
            Util.close(in);
            Util.close(out);
            Util.close(sock);
            synchronized(connections) {
                connections.remove(this);
            }
        }


        public void run() {
            while(!sock.isClosed()) {
                try {
                    Frame frame=readFrame(in);
                    System.out.println("frame = " + frame);
                }
                catch(IOException ex) {
                    log.error("failure reading frame", ex);
                    stop(); // ??
                }

            }
        }

        private Frame readFrame(DataInputStream in) throws IOException {
            String verb=Util.readLine(in);
            if(verb == null)
                throw new EOFException("reading verb");

            Map<String,String> headers=new HashMap<String,String>();
            byte[] body=null;

            for(;;) {
                String header=Util.readLine(in);
                if(header == null)
                    throw new EOFException("reading header");
                if(header.length() == 0)
                    break;
                int index=header.indexOf(":");
                if(index != -1)
                    headers.put(header.substring(0, index).trim(), header.substring(index+1).trim());
            }

            if(headers.containsKey("length")) {
                int length=Integer.parseInt(headers.get("length"));
                body=new byte[length];
                in.read(body, 0, body.length);
            }
            else {
                ByteBuffer buf=ByteBuffer.allocate(500);
                boolean terminate=false;
                for(;;) {
                    int c=in.read();
                    if(c == -1 || c == 0)
                        terminate=true;

                    if(buf.remaining() == 0 || terminate) {
                        if(body == null) {
                            body=new byte[buf.position()];
                            System.arraycopy(buf.array(), buf.arrayOffset(), body, 0, buf.position());
                        }
                        else {
                            byte[] tmp=new byte[body.length + buf.position()];
                            System.arraycopy(body, 0, tmp, 0, body.length);
                            try {
                                System.arraycopy(buf.array(), buf.arrayOffset(), tmp, body.length, buf.position());
                            }
                            catch(Throwable t) {
                            }
                            body=tmp;
                        }
                        buf.rewind();
                    }

                    if(terminate)
                        break;

                    buf.put((byte)c);
                }
            }


            return new Frame(verb, headers, body);
        }
    }

    protected static class Frame {
        final String verb;
        final Map<String,String> headers;
        final byte[] body;

        public Frame(String verb, Map<String, String> headers, byte[] body) {
            this.verb=verb;
            this.headers=headers;
            this.body=body;
        }

        public String toString() {
            StringBuilder sb=new StringBuilder();
            sb.append(verb).append("\n");
            if(headers != null && !headers.isEmpty()) {
                for(Map.Entry<String,String> entry: headers.entrySet())
                    sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            if(body != null && body.length > 0) {
                sb.append("body: ").append(body.length).append(" bytes");
            }
            return sb.toString();
        }
    }
}