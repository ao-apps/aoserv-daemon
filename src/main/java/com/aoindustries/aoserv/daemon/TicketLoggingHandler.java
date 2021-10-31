/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2009-2013, 2017, 2018, 2020  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
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
