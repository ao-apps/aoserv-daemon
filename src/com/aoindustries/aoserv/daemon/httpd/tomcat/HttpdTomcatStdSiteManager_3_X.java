package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Manages HttpdTomcatStdSite version 3.X configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatStdSiteManager_3_X extends HttpdTomcatStdSiteManager {

    HttpdTomcatStdSiteManager_3_X(HttpdTomcatStdSite tomcatStdSite) {
        super(tomcatStdSite);
    }

    /**
     * Only supports ajp12
     */
    @Override
    protected HttpdWorker getHttpdWorker() throws IOException, SQLException {
        AOServConnector conn = AOServDaemon.getConnector();
        List<HttpdWorker> workers = tomcatSite.getHttpdWorkers();

        // Try ajp12 next
        for(HttpdWorker hw : workers) {
            if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP12)) return hw;
        }
        throw new SQLException("Couldn't ajp12");
    }
}
