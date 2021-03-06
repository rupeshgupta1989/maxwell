package com.zendesk.maxwell.schema;

import com.zendesk.maxwell.BinlogPosition;
import snaq.db.ConnectionPool;

import java.sql.SQLException;
import java.util.concurrent.TimeoutException;

/**
 * a schema position object that doesn't write its position out.
 * useful for "replay" mode.
 */
public class ReadOnlyMysqlPositionStore extends MysqlPositionStore {
	public ReadOnlyMysqlPositionStore(ConnectionPool pool, Long serverID, String dbName, String clientID) {
		super(pool, serverID, dbName, clientID);
	}

	@Override
	public void start() { }

	@Override
	public void stopLoop() throws TimeoutException { }
}
