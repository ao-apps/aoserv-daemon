package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Manages HttpdTomcatStdSite configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatStdSiteManager extends HttpdTomcatSiteManager {

    /**
     * Gets the specific manager for one type of web site.
     */
    static HttpdTomcatStdSiteManager getInstance(HttpdTomcatStdSite stdSite) throws IOException, SQLException {
        AOServConnector connector=AOServDaemon.getConnector();
        HttpdTomcatVersion htv=stdSite.getHttpdTomcatSite().getHttpdTomcatVersion();
        if(htv.isTomcat3_1(connector)) return new HttpdTomcatStdSiteManager_3_1(stdSite);
        if(htv.isTomcat3_2_4(connector)) return new HttpdTomcatStdSiteManager_3_2_4(stdSite);
        if(htv.isTomcat4_1_X(connector)) return new HttpdTomcatStdSiteManager_4_1_X(stdSite);
        if(htv.isTomcat5_5_X(connector)) return new HttpdTomcatStdSiteManager_5_5_X(stdSite);
        if(htv.isTomcat6_0_X(connector)) return new HttpdTomcatStdSiteManager_6_0_X(stdSite);
        throw new SQLException("Unsupported version of standard Tomcat: "+htv.getTechnologyVersion(connector).getVersion()+" on "+stdSite);
    }

    final protected HttpdTomcatStdSite tomcatStdSite;
    
    HttpdTomcatStdSiteManager(HttpdTomcatStdSite tomcatStdSite) {
        super(tomcatStdSite.getHttpdTomcatSite());
        this.tomcatStdSite = tomcatStdSite;
    }

    /**
     * Standard sites always have worker directly attached.
     */
    protected HttpdWorker getHttpdWorker() throws IOException, SQLException {
        AOServConnector conn = AOServDaemon.getConnector();
        List<HttpdWorker> workers = tomcatSite.getHttpdWorkers();

        // Prefer ajp13
        for(HttpdWorker hw : workers) {
            if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP13)) return hw;
        }
        // Try ajp12 next
        for(HttpdWorker hw : workers) {
            if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP12)) return hw;
        }
        throw new SQLException("Couldn't find either ajp13 or ajp12");
    }
}
