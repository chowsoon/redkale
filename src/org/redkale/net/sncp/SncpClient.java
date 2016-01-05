/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net.sncp;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import org.redkale.convert.bson.*;
import org.redkale.convert.json.*;
import org.redkale.net.*;
import static org.redkale.net.sncp.SncpRequest.*;
import org.redkale.service.*;
import org.redkale.util.*;
import org.redkale.service.DynCall;

/**
 *
 * <p> 详情见: http://www.redkale.org
 * @author zhangjx
 */
public final class SncpClient {

    protected static final class SncpAction {

        protected final DLong actionid;

        protected final Method method;

        protected final Type resultTypes;  //void 必须设为 null

        protected final Type[] paramTypes;

        protected final Attribute[] paramAttrs; // 为null表示无DynCall处理，index=0固定为null, 其他为参数标记的DynCall回调方法

        protected final int handlerFuncParamIndex;

        protected final int handlerAttachParamIndex;

        protected final int addressTargetParamIndex;

        protected final int addressSourceParamIndex;

        public SncpAction(Method method, DLong actionid) {
            this.actionid = actionid;
            Type rt = method.getGenericReturnType();
            if (rt instanceof TypeVariable) {
                TypeVariable tv = (TypeVariable) rt;
                if (tv.getBounds().length == 1) rt = tv.getBounds()[0];
            }
            this.resultTypes = rt == void.class ? null : rt;
            this.paramTypes = method.getGenericParameterTypes();
            this.method = method;
            Annotation[][] anns = method.getParameterAnnotations();
            int targetAddrIndex = -1;
            int sourceAddrIndex = -1;
            int handlerAttachIndex = -1;
            int handlerFuncIndex = -1;
            boolean hasattr = false;
            Attribute[] atts = new Attribute[paramTypes.length + 1];
            if (anns.length > 0) {
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (CompletionHandler.class.isAssignableFrom(params[i])) {
                        handlerFuncIndex = i;
                        break;
                    }
                }
                for (int i = 0; i < anns.length; i++) {
                    if (anns[i].length > 0) {
                        for (Annotation ann : anns[i]) {
                            if (ann.annotationType() == DynAttachment.class) {
                                handlerAttachIndex = i;
                            } else if (ann.annotationType() == DynTargetAddress.class && SocketAddress.class.isAssignableFrom(params[i])) {
                                targetAddrIndex = i;
                            } else if (ann.annotationType() == DynSourceAddress.class && SocketAddress.class.isAssignableFrom(params[i])) {
                                sourceAddrIndex = i;
                            }
                        }
                        for (Annotation ann : anns[i]) {
                            if (ann.annotationType() == DynCall.class) {
                                try {
                                    atts[i + 1] = ((DynCall) ann).value().newInstance();
                                    hasattr = true;
                                } catch (Exception e) {
                                    logger.log(Level.SEVERE, DynCall.class.getSimpleName() + ".attribute cannot a newInstance for" + method, e);
                                }
                                break;
                            }
                        }
                    }
                }
            }
            this.addressTargetParamIndex = targetAddrIndex;
            this.addressSourceParamIndex = sourceAddrIndex;
            this.handlerFuncParamIndex = handlerFuncIndex;
            this.handlerAttachParamIndex = handlerAttachIndex;
            this.paramAttrs = hasattr ? atts : null;
            if (this.handlerFuncParamIndex >= 0 && method.getReturnType() != void.class) throw new RuntimeException(method + " has CompletionHandler type parameter but return type is not void");
        }

        @Override
        public String toString() {
            return "{" + actionid + "," + (method == null ? "null" : method.getName()) + "}";
        }
    }

    private static final Logger logger = Logger.getLogger(SncpClient.class.getSimpleName());

    private final boolean finest = logger.isLoggable(Level.FINEST);

    protected final JsonConvert jsonConvert = JsonFactory.root().getConvert();

    protected final String name;

    protected final boolean remote;

    private final Class serviceClass;

    protected final InetSocketAddress address;

    protected final HashSet<String> groups;

    private final byte[] addrBytes;

    private final int addrPort;

    protected final DLong serviceid;

    protected final DLong nameid;

    protected final SncpAction[] actions;

    protected final Consumer<Runnable> executor;

    public SncpClient(final String serviceName, final Consumer<Runnable> executor, final DLong serviceid, boolean remote, final Class serviceClass,
            boolean onlySncpDyn, final InetSocketAddress clientAddress, final HashSet<String> groups) {
        this.remote = remote;
        this.executor = executor;
        this.serviceClass = serviceClass;
        this.address = clientAddress;
        this.groups = groups;
        //if (subLocalClass != null && !serviceClass.isAssignableFrom(subLocalClass)) throw new RuntimeException(subLocalClass + " is not " + serviceClass + " sub class ");
        this.name = serviceName;
        this.nameid = Sncp.hash(serviceName);
        this.serviceid = serviceid;
        final List<SncpAction> methodens = new ArrayList<>();
        //------------------------------------------------------------------------------
        for (java.lang.reflect.Method method : parseMethod(serviceClass, onlySncpDyn)) { //远程模式下onlySncpDyn = false
            SncpAction en = new SncpAction(method, Sncp.hash(method));
            methodens.add(en);
        }
        this.actions = methodens.toArray(new SncpAction[methodens.size()]);
        this.addrBytes = clientAddress == null ? new byte[4] : clientAddress.getAddress().getAddress();
        this.addrPort = clientAddress == null ? 0 : clientAddress.getPort();
    }

    public InetSocketAddress getClientAddress() {
        return address;
    }

    public DLong getNameid() {
        return nameid;
    }

    public DLong getServiceid() {
        return serviceid;
    }

    public int getActionCount() {
        return actions.length;
    }

    @Override
    public String toString() {
        String service = serviceClass.getName();
        if (remote) service = service.replace(Sncp.LOCALPREFIX, Sncp.REMOTEPREFIX);
        return this.getClass().getSimpleName() + "(service = " + service + ", serviceid = " + serviceid + ", nameid = " + nameid
                + ", name = '" + name + "', address = " + (address == null ? "" : (address.getHostString() + ":" + address.getPort()))
                + ", groups = " + groups + ", actions.size = " + actions.length + ")";
    }

    public static List<Method> parseMethod(final Class serviceClass, boolean onlySncpDyn) { //远程模式下onlySncpDyn = false
        final List<Method> list = new ArrayList<>();
        final List<Method> multis = new ArrayList<>();
        final Map<DLong, Method> actionids = new HashMap<>();

        for (final java.lang.reflect.Method method : serviceClass.getMethods()) {
            if (method.isSynthetic()) continue;
            final int mod = method.getModifiers();
            if (Modifier.isStatic(mod)) continue;
            if (Modifier.isFinal(mod)) continue;
            if (method.getName().equals("getClass") || method.getName().equals("toString")) continue;
            if (method.getName().equals("equals") || method.getName().equals("hashCode")) continue;
            if (method.getName().equals("notify") || method.getName().equals("notifyAll") || method.getName().equals("wait")) continue;
            if (method.getName().equals("init") || method.getName().equals("destroy") || method.getName().equals("name")) continue;
            if (onlySncpDyn && method.getAnnotation(SncpDyn.class) == null) continue;
            DLong actionid = Sncp.hash(method);
            Method old = actionids.get(actionid);
            if (old != null) {
                if (old.getDeclaringClass().equals(method.getDeclaringClass()))
                    throw new RuntimeException(serviceClass.getName() + " have one more same action(Method=" + method + ", " + old + ", actionid=" + actionid + ")");
                continue;
            }
            actionids.put(actionid, method);
            if (method.getAnnotation(SncpDyn.class) != null) {
                multis.add(method);
            } else {
                list.add(method);
            }
        }
        list.addAll(multis);
        if (onlySncpDyn && list.size() > 1) {
            list.sort((m1, m2) -> m1.getAnnotation(SncpDyn.class).index() - m2.getAnnotation(SncpDyn.class).index());
        }
        return list;
    }

    public <T> T remote(final BsonConvert convert, Transport transport, final int index, final Object... params) {
        final SncpAction action = actions[index];
        final CompletionHandler handlerFunc = action.handlerFuncParamIndex >= 0 ? (CompletionHandler) params[action.handlerFuncParamIndex] : null;
        if (action.handlerFuncParamIndex >= 0) params[action.handlerFuncParamIndex] = null;
        Future<byte[]> future = remote0(handlerFunc, convert, transport, action, params);
        if (handlerFunc != null) return null;
        final BsonReader in = convert.pollBsonReader();
        try {
            in.setBytes(future.get(5, TimeUnit.SECONDS));
            byte i;
            while ((i = in.readByte()) != 0) {
                final Attribute attr = action.paramAttrs[i];
                attr.set(params[i - 1], convert.convertFrom(in, attr.type()));
            }
            return convert.convertFrom(in, action.resultTypes);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.log(Level.SEVERE, actions[index].method + " sncp (params: " + jsonConvert.convertTo(params) + ") remote error", e);
            throw new RuntimeException(actions[index].method + " sncp remote error", e);
        } finally {
            convert.offerBsonReader(in);
        }
    }

    public <T> void remote(final BsonConvert convert, Transport[] transports, boolean run, final int index, final Object... params) {
        if (!run || transports == null || transports.length < 1) return;
        remote(convert, transports[0], index, params);
        for (int i = 1; i < transports.length; i++) {
            remote0(null, convert, transports[i], actions[index], params);
        }
    }

    public <T> void asyncRemote(final BsonConvert convert, Transport[] transports, boolean run, final int index, final Object... params) {
        if (!run || transports == null || transports.length < 1) return;
        if (executor != null) {
            executor.accept(() -> {
                remote(convert, transports, run, index, params);
            });
        } else {
            remote(convert, transports, run, index, params);
        }
    }

    private Future<byte[]> remote0(final CompletionHandler handler, final BsonConvert convert, final Transport transport, final SncpAction action, final Object... params) {
        Type[] myparamtypes = action.paramTypes;
        if (action.addressSourceParamIndex >= 0) params[action.addressSourceParamIndex] = this.address;
        final BsonWriter writer = convert.pollBsonWriter(transport.getBufferSupplier()); // 将head写入
        writer.writeTo(DEFAULT_HEADER);
        for (int i = 0; i < params.length; i++) {
            convert.convertTo(writer, myparamtypes[i], params[i]);
        }
        final int reqBodyLength = writer.count() - HEADER_SIZE; //body总长度
        final long seqid = System.nanoTime();
        final DLong actionid = action.actionid;
        final SocketAddress addr = action.addressTargetParamIndex >= 0 ? (SocketAddress) params[action.addressTargetParamIndex] : null;
        final AsyncConnection conn = transport.pollConnection(addr);
        if (conn == null || !conn.isOpen()) {
            logger.log(Level.SEVERE, action.method + " sncp (params: " + jsonConvert.convertTo(params) + ") cannot connect " + (conn == null ? addr : conn.getRemoteAddress()));
            throw new RuntimeException("sncp " + (conn == null ? addr : conn.getRemoteAddress()) + " cannot connect");
        }
        final ByteBuffer[] sendBuffers = writer.toBuffers();
        fillHeader(sendBuffers[0], seqid, actionid, reqBodyLength);

        final ByteBuffer buffer = transport.pollBuffer();
        final SncpFuture<byte[]> future = new SncpFuture();
        conn.write(sendBuffers, sendBuffers, new CompletionHandler<Integer, ByteBuffer[]>() {

            @Override
            public void completed(Integer result, ByteBuffer[] attachments) {
                int index = -1;
                for (int i = 0; i < attachments.length; i++) {
                    if (attachments[i].hasRemaining()) {
                        index = i;
                        break;
                    } else {
                        transport.offerBuffer(attachments[i]);
                    }
                }
                if (index == 0) {
                    conn.write(attachments, attachments, this);
                    return;
                } else if (index > 0) {
                    ByteBuffer[] newattachs = new ByteBuffer[attachments.length - index];
                    System.arraycopy(attachments, index, newattachs, 0, newattachs.length);
                    conn.write(newattachs, newattachs, this);
                    return;
                }
                //----------------------- 读取返回结果 -------------------------------------
                buffer.clear();
                conn.read(buffer, null, new CompletionHandler<Integer, Void>() {

                    private byte[] body;

                    private int received;

                    @Override
                    public void completed(Integer count, Void attachment2) {
                        if (count < 1 && buffer.remaining() == buffer.limit()) {   //没有数据可读
                            future.set(new RuntimeException(action.method + " sncp[" + conn.getRemoteAddress() + "] remote no response data"));
                            transport.offerBuffer(buffer);
                            transport.offerConnection(true, conn);
                            return;
                        }
                        if (received < 1 && buffer.limit() < buffer.remaining() + HEADER_SIZE) { //header都没读全
                            conn.read(buffer, attachment2, this);
                            return;
                        }
                        buffer.flip();
                        if (received > 0) {
                            int offset = this.received;
                            this.received += buffer.remaining();
                            buffer.get(body, offset, Math.min(buffer.remaining(), this.body.length - offset));
                            if (this.received < this.body.length) {// 数据仍然不全，需要继续读取          
                                buffer.clear();
                                conn.read(buffer, attachment2, this);
                            } else {
                                success();
                            }
                            return;
                        }
                        checkResult(seqid, action, buffer);

                        final int respBodyLength = buffer.getInt();
                        final int retcode = buffer.getInt();
                        if (retcode != 0) {
                            logger.log(Level.SEVERE, action.method + " sncp (params: " + jsonConvert.convertTo(params) + ") deal error (retcode=" + retcode + ", retinfo=" + SncpResponse.getRetCodeInfo(retcode) + ")");
                            throw new RuntimeException("remote service(" + action.method + ") deal error (retcode=" + retcode + ", retinfo=" + SncpResponse.getRetCodeInfo(retcode) + ")");
                        }

                        if (respBodyLength > buffer.remaining()) { // 数据不全，需要继续读取
                            this.body = new byte[respBodyLength];
                            this.received = buffer.remaining();
                            buffer.get(body, 0, this.received);
                            buffer.clear();
                            conn.read(buffer, attachment2, this);
                        } else {
                            this.body = new byte[respBodyLength];
                            buffer.get(body, 0, respBodyLength);
                            success();
                        }
                    }

                    public void success() {
                        future.set(this.body);
                        transport.offerBuffer(buffer);
                        transport.offerConnection(false, conn);
                        if (handler != null) {
                            final Object handlerAttach = action.handlerAttachParamIndex >= 0 ? params[action.handlerAttachParamIndex] : null;
                            final BsonReader in = convert.pollBsonReader();
                            try {
                                in.setBytes(this.body);
                                byte i;
                                while ((i = in.readByte()) != 0) {
                                    final Attribute attr = action.paramAttrs[i];
                                    attr.set(params[i - 1], convert.convertFrom(in, attr.type()));
                                }
                                Object rs = convert.convertFrom(in, action.resultTypes);
                                handler.completed(rs, handlerAttach);
                            } catch (Exception e) {
                                handler.failed(e, handlerAttach);
                            } finally {
                                convert.offerBsonReader(in);
                            }
                        }
                    }

                    @Override
                    public void failed(Throwable exc, Void attachment2) {
                        logger.log(Level.SEVERE, action.method + " sncp (params: " + jsonConvert.convertTo(params) + ") remote read exec failed", exc);
                        future.set(new RuntimeException(action.method + " sncp remote exec failed"));
                        transport.offerBuffer(buffer);
                        transport.offerConnection(true, conn);
                        if (handler != null) {
                            final Object handlerAttach = action.handlerAttachParamIndex >= 0 ? params[action.handlerAttachParamIndex] : null;
                            handler.failed(exc, handlerAttach);
                        }
                    }
                });
            }

            @Override
            public void failed(Throwable exc, ByteBuffer[] attachment) {
                logger.log(Level.SEVERE, action.method + " sncp (params: " + jsonConvert.convertTo(params) + ") remote write exec failed", exc);
                transport.offerBuffer(buffer);
                transport.offerConnection(true, conn);
            }
        });
        return future;
    }

    private void checkResult(long seqid, final SncpAction action, ByteBuffer buffer) {
        long rseqid = buffer.getLong();
        if (rseqid != seqid) throw new RuntimeException("sncp(" + action.method + ") response.seqid = " + seqid + ", but request.seqid =" + rseqid);
        if (buffer.getChar() != HEADER_SIZE) throw new RuntimeException("sncp(" + action.method + ") buffer receive header.length not " + HEADER_SIZE);
        DLong rserviceid = DLong.read(buffer);
        if (!rserviceid.equals(serviceid)) throw new RuntimeException("sncp(" + action.method + ") response.serviceid = " + serviceid + ", but request.serviceid =" + rserviceid);
        DLong rnameid = DLong.read(buffer);
        if (!rnameid.equals(nameid)) throw new RuntimeException("sncp(" + action.method + ") response.nameid = " + nameid + ", but receive nameid =" + rnameid);
        DLong raction = DLong.read(buffer);
        if (!action.actionid.equals(raction)) throw new RuntimeException("sncp(" + action.method + ") response.actionid = " + action.actionid + ", but request.actionid =(" + raction + ")");
        buffer.getInt();  //地址
        buffer.getChar(); //端口
    }

    private void fillHeader(ByteBuffer buffer, long seqid, DLong actionid, int bodyLength) {
        //---------------------head----------------------------------
        final int currentpos = buffer.position();
        buffer.position(0);
        buffer.putLong(seqid); //序列号
        buffer.putChar((char) HEADER_SIZE); //header长度
        DLong.write(buffer, this.serviceid);
        DLong.write(buffer, this.nameid);
        DLong.write(buffer, actionid);
        buffer.put(addrBytes);
        buffer.putChar((char) this.addrPort);
        buffer.putInt(bodyLength); //body长度        
        buffer.putInt(0); //结果码， 请求方固定传0
        buffer.position(currentpos);
    }

    protected static final class SncpFuture<T> implements Future<T> {

        private volatile boolean done;

        private T result;

        private RuntimeException ex;

        public SncpFuture() {
        }

        public SncpFuture(T result) {
            this.result = result;
            this.done = true;
        }

        public void set(T result) {
            this.result = result;
            this.done = true;
            synchronized (this) {
                notifyAll();
            }
        }

        public void set(RuntimeException ex) {
            this.ex = ex;
            this.done = true;
            synchronized (this) {
                notifyAll();
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            if (done) {
                if (ex != null) throw ex;
                return result;
            }
            synchronized (this) {
                if (!done) wait(10_000);
            }
            if (done) {
                if (ex != null) throw ex;
                return result;
            }
            throw new InterruptedException();
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            if (done) {
                if (ex != null) throw ex;
                return result;
            }
            synchronized (this) {
                if (!done) wait(unit.toMillis(timeout));
            }
            if (done) {
                if (ex != null) throw ex;
                return result;
            }
            throw new TimeoutException();
        }
    }
}