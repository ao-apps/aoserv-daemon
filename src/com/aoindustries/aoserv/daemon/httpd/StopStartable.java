package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import java.io.IOException;
import java.sql.SQLException;

/**
 * Indicates something may be stopped and started.
 *
 * @author  AO Industries, Inc.
 */
public interface StopStartable {

    /**
     * Stops all processes for this website if it is running.
     * 
     * @return  <code>true</code> if actually stopped or <code>false</code> if was already stopped
     */
    boolean stop() throws IOException, SQLException;

    /**
     * Starts all processes for this website if it is not running.
     * 
     * @return  <code>true</code> if actually started or <code>false</code> if was already started
     */
    boolean start() throws IOException, SQLException;
}
