package com.aoindustries.aoserv.daemon.backup;

/*
 * Copyright 2001-2006 by AO Industries, Inc.,
 * 2200 Dogwood Ct N, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.backup.*;
import com.aoindustries.aoserv.client.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.sql.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;

public class RestoreServer {

    public static void main(String[] args) {
        Profiler.startProfile(Profiler.UNKNOWN, RestoreServer.class, "main(String[])", null);
        try {
            TerminalWriter out=new TerminalWriter(System.out);
            TerminalWriter err=new TerminalWriter(System.err);
            if(args.length==2 || args.length==3) {
                try {
                    String username=AOSH.getConfigUsername(System.in, out);
                    String password=AOSH.getConfigPassword(System.in, out);
                    AOServConnector conn=AOServConnector.getConnector(username, password, new StandardErrorHandler());
                    String server=args[0];
                    String destinationDirectory=args[1];
                    long time;
                    if(args.length==3) time=SQLUtility.getDate(args[2]).getTime();
                    else time=-1;

                    restoreServer(out, err, conn, server, destinationDirectory, time);
                    System.exit(0);
                } catch(IOException exc) {
                    ErrorPrinter.printStackTraces(exc, err);
                    err.flush();
                    System.exit(2);
                } catch(SQLException exc) {
                    ErrorPrinter.printStackTraces(exc, err);
                    err.flush();
                    System.exit(3);
                }
            } else {
                err.println("usage: "+RestoreServer.class.getName()+" <server> <destination_directory> [<backup_date>]");
                err.flush();
                System.exit(1);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    public static void restoreServer(TerminalWriter out, TerminalWriter err, AOServConnector conn, String server, String destinationDirectory, long time) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, RestoreServer.class, "restoreServer(String,String,long)", null);
        try {
            RestoreFiles.restoreFiles(out, err, conn, server, null, destinationDirectory, time);

            // Locate the server
            Server se=conn.servers.get(server);
            if(se==null) throw new SQLException("Unable to find Server: "+server);

            // Restore the MySQL database
            
            // Restore the PostgreSQL database
            
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}