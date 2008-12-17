package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.io.unix.UnixFile;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * Manages HttpdSharedTomcat version 6.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_6_0_X extends HttpdSharedTomcatManager {

    HttpdSharedTomcatManager_6_0_X(HttpdSharedTomcat sharedTomcat) {
        super(sharedTomcat);
    }
    
    TomcatCommon getTomcatCommon() {
        return TomcatCommon_6_0_X.getInstance();
    }

    void buildSharedTomcatDirectory(UnixFile sharedTomcatDirectory, List<File> deleteFileList, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
        throw new SQLException("TODO: Implement method");
    }
}
