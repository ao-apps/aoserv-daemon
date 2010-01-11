package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2001-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.validator.MySQLUserId;
import com.aoindustries.aoserv.client.validator.ValidationException;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.util.ErrorPrinter;
import com.aoindustries.util.StringUtility;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;

/**
 * Encapsulates the output of the /usr/bin/mysqladmin command.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLAdmin extends DBReportData {

    final public int questions;
    final public int slow_queries;
    final public int opens;
    final public int flush_tables;
    final public int open_tables;
    final public float queries_per_second;

    public MySQLAdmin() throws IOException, ValidationException {
        MySQLUserId user=AOServDaemonConfiguration.getMysqlUser();
        String password=AOServDaemonConfiguration.getMysqlPassword();
        if(user!=null && password!=null && password.length()>0) {
            String[] cmd={
                "/usr/bin/mysqladmin",
                "-h",
                "127.0.0.1",
                "-u",
                user.getId(),
                "--password="+password,
                "status"
            };
            String line;
            Process P=Runtime.getRuntime().exec(cmd);
            try {
                P.getOutputStream().close();
                BufferedReader in=new BufferedReader(new InputStreamReader(P.getInputStream()));
                try {
                    line=in.readLine();
                } finally {
                    in.close();
                }
            } finally {
                try {
                    int retCode=P.waitFor();
                    if(retCode!=0) throw new IOException("/usr/bin/mysqladmin returned with non-zero status: "+retCode);
                } catch(InterruptedException err) {
                    InterruptedIOException ioErr=new InterruptedIOException();
                    ioErr.initCause(err);
                    throw ioErr;
                }
            }

            // Parse out the number of users
            String[] words=StringUtility.splitString(line);
            numUsers=Integer.parseInt(words[3]);
            questions=Integer.parseInt(words[5]);
            slow_queries=Integer.parseInt(words[8]);
            opens=Integer.parseInt(words[10]);
            flush_tables=Integer.parseInt(words[13]);
            open_tables=Integer.parseInt(words[16]);
            queries_per_second=Float.parseFloat(words[21]);
        } else {
            numUsers=questions=slow_queries=opens=flush_tables=open_tables=0;
            queries_per_second=0;
        }
    }

    public static void main(String[] args) {
        try {
            System.err.println(new MySQLAdmin());
            System.exit(0);
        } catch(IOException err) {
            ErrorPrinter.printStackTraces(err);
            System.exit(1);
        } catch(ValidationException err) {
            ErrorPrinter.printStackTraces(err);
            System.exit(2);
        }
    }

    @Override
    public String toString() {
        return
            super.toString()
            + "&questions="+questions
            + "&slow_queries="+slow_queries
            + "&opens="+opens
            + "&flush_tables="+flush_tables
            + "&open_tables="+open_tables
            + "&queries_per_second="+queries_per_second
        ;
    }
}
