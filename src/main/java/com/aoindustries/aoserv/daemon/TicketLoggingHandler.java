/*
 * Copyright 2009-2013, 2017, 2018, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon;

/**
 * Provides static access to the logging facilities.  The logs are written
 * into the AOServ ticket system under the type "logs".
 *
 * @author  AO Industries, Inc.
 */
public class TicketLoggingHandler extends com.aoindustries.aoserv.client.ticket.TicketLoggingHandler {

	/**
	 * Public constructor required so can be specified in <code>logging.properties</code>.
	 */
	public TicketLoggingHandler() {
		super(
			AOServDaemonConfiguration.getServerHostname(),
			AOServDaemon.getConnector(),
			"aoserv.aoserv_daemon"
		);
	}
}
