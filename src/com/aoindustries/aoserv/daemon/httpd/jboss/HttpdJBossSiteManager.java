package com.aoindustries.aoserv.daemon.httpd.jboss;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.daemon.httpd.tomcat.HttpdTomcatSiteManager;
import com.aoindustries.aoserv.client.HttpdJBossSite;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages HttpdJBossSite configurations.
 *
 * @author  AO Industries, Inc.
 */
public abstract class HttpdJBossSiteManager extends HttpdTomcatSiteManager {

    /**
     * Gets the specific manager for one type of web site.
     */
    public static HttpdJBossSiteManager getInstance(HttpdJBossSite jbossSite) throws IOException, SQLException {
        AOServConnector connector=AOServDaemon.getConnector();
        String jbossVersion = jbossSite.getHttpdJBossVersion().getTechnologyVersion(connector).getVersion();
        if(jbossVersion.equals("2.2.2")) return new HttpdJBossSiteManager_2_2_2(jbossSite);
        throw new SQLException("Unsupported version of standard JBoss: "+jbossVersion+" on "+jbossSite);
    }

    final protected HttpdJBossSite jbossSite;
    
    HttpdJBossSiteManager(HttpdJBossSite jbossSite) {
        super(jbossSite.getHttpdTomcatSite());
        this.jbossSite = jbossSite;
    }
}
