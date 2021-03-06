package com.zendesk.maxwell.schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.zendesk.maxwell.BinlogPosition;
import com.zendesk.maxwell.RunLoopProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import snaq.db.ConnectionPool;

public class MysqlPositionStore extends RunLoopProcess implements Runnable {
	static final Logger LOGGER = LoggerFactory.getLogger(MysqlPositionStore.class);
	private final Long serverID;
	private final AtomicReference<BinlogPosition> position;
	private final AtomicReference<BinlogPosition> storedPosition;
	private final AtomicBoolean run;
	private Thread thread;
	private String schemaDatabaseName;
	private String clientID;
	private final ConnectionPool connectionPool;
	private SQLException exception;

	public MysqlPositionStore(ConnectionPool pool, Long serverID, String dbName, String clientID) {
		this.connectionPool = pool;
		this.serverID = serverID;
		this.schemaDatabaseName = dbName;
		this.clientID = clientID;
		this.position = new AtomicReference<>();
		this.storedPosition = new AtomicReference<>();
		this.exception = null;
		this.run = new AtomicBoolean(false);
	}

	public void start() {
		this.thread = new Thread(this, "Position Flush Thread");
		thread.start();
	}

	public void stopLoop() throws TimeoutException {
		this.requestStop();
		thread.interrupt();
		super.stopLoop();
	}

	@Override
	protected void beforeStop() {
		if ( exception == null ) {
			LOGGER.info("Storing final position: " + position.get());
			store(position.get());
		}
	}

	@Override
	public void run() {
		try {
			runLoop();
		} catch ( Exception e ) {
			// this code should never be hit.  There is, I suppose,
			// a design flaw in my code, but at a certain point
			// the whole inheritance + exception handling thing
			// just started to drive me nuts.
			LOGGER.error("Hit unexpected exception in MysqlPositionStore thread: " + e);
		}
	}

	public void work() throws Exception {
		BinlogPosition newPosition = position.get();

		if ( newPosition != null && newPosition.newerThan(storedPosition.get()) ) {
			store(newPosition);
		}

		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) { }
	}


	private void store(BinlogPosition newPosition) {
		if ( newPosition == null )
			return;

		String sql = "INSERT INTO `positions` set "
				+ "server_id = ?, "
				+ "binlog_file = ?, "
				+ "binlog_position = ?, "
				+ "client_id = ? "
				+ "ON DUPLICATE KEY UPDATE binlog_file=?, binlog_position=?";
		try( Connection c = getConnection() ){
			PreparedStatement s = c.prepareStatement(sql);

			LOGGER.debug("Writing binlog position to " + this.schemaDatabaseName + ".positions: " + newPosition);
			s.setLong(1, serverID);
			s.setString(2, newPosition.getFile());
			s.setLong(3, newPosition.getOffset());
			s.setString(4, clientID);
			s.setString(5, newPosition.getFile());
			s.setLong(6, newPosition.getOffset());

			s.execute();
			storedPosition.set(newPosition);
		} catch ( SQLException e ) {
			LOGGER.error("received SQLException while trying to save to " + this.schemaDatabaseName + ".positions: ");
			LOGGER.error(e.getLocalizedMessage());
			this.requestStop();
			this.exception = e;
		}
	}

	public synchronized void set(BinlogPosition p) {
		if ( position.get() == null || p.newerThan(position.get()) )
			position.set(p);
	}

	public BinlogPosition get() throws SQLException {
		BinlogPosition p = position.get();
		if ( p != null )
			return p;

		try ( Connection c = getConnection() ) {
			PreparedStatement s = c.prepareStatement("SELECT * from `positions` where server_id = ? and client_id = ?");
			s.setLong(1, serverID);
			s.setString(2, clientID);

			ResultSet rs = s.executeQuery();
			if ( !rs.next() )
				return null;

			return new BinlogPosition(rs.getLong("binlog_position"), rs.getString("binlog_file"));
		}
	}

	private Connection getConnection() throws SQLException {
		Connection conn = this.connectionPool.getConnection();
		conn.setCatalog(this.schemaDatabaseName);
		return conn;
	}

	public SQLException getException() {
		return this.exception;
	}

}
