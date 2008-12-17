package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Manages HttpdTomcatSharedSite version 3.X configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatSharedSiteManager_3_X extends HttpdTomcatSharedSiteManager {

    HttpdTomcatSharedSiteManager_3_X(HttpdTomcatSharedSite tomcatSharedSite) {
        super(tomcatSharedSite);
    }

    /**
     * Shared Tomcat 3.X sites have worker directly attached like standard sites,
     * they also only support ajp12.
     */
    @Override
    protected HttpdWorker getHttpdWorker() throws IOException, SQLException {
        AOServConnector conn = AOServDaemon.getConnector();
        List<HttpdWorker> workers = tomcatSite.getHttpdWorkers();

        // Only ajp12 supported
        for(HttpdWorker hw : workers) {
            if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP12)) return hw;
        }

        throw new SQLException("Couldn't find ajp12");
    }
}
