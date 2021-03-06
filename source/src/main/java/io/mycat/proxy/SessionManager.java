package io.mycat.proxy;

import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Collection;

import io.mycat.mycat2.MySQLSession;
import io.mycat.mycat2.tasks.AsynTaskCallBack;
import io.mycat.proxy.buffer.BufferPool;

/**
 * 用来处理新的连接请求并创建Session
 * 
 * @author wuzhihui.chenjunwen
 *
 */
public interface SessionManager<T extends Session> {

	/**
	 * 针对新创建的连接，产生对应的Session对象，
	 *
	 * @author cjw
	 * 通道必须是已经连接的
	 * @param bufPool
	 *            用来获取Buffer的Pool
	 * @param nioSelector
	 *            注册到对应的Selector上
	 * @param channel
	 *            Channel对象
	 * @return T session
	 * @throws IOException
	 */
	public T createSessionForConnectedChannel(Object keyAttachement, BufferPool bufPool, Selector nioSelector, SocketChannel channel)
			throws IOException;

	/**
	 * 返回所有的Session，此方法可能会消耗性能，如果仅仅统计数量，不建议调用此方法
	 * 
	 * @return
	 */
	public Collection<T> getAllSessions();

	/**
	 * 获取当前Session数量
	 * @return count
	 */
	public int curSessionCount();

	/**
	 * 获取默认的Session处理句柄
	 * @return
	 */
	public NIOHandler<T> getDefaultSessionHandler();

	/**
	 * 从管理器中移除Session
	 * @param session
	 */
	public void removeSession(T session);

}
