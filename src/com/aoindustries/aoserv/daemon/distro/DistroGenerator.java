package com.aoindustries.aoserv.daemon.distro;

/*
 * Copyright 2001-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.md5.*;
import com.aoindustries.profiler.*;
import com.aoindustries.sql.*;
import com.aoindustries.util.*;
import com.aoindustries.util.sort.*;
import java.io.*;
import java.util.*;

/**
 * Creates the server distribution database contents.
 *
 * @author  AO Industries, Inc.
 */
final public class DistroGenerator extends Thread {

    private static final int NUM_THREADS=4;

    private static final int ROWS_PER_TRANSACTION=1000;

    private static final DistroGenerator[] generators=new DistroGenerator[NUM_THREADS];
    private static int activeGeneratorCount=0;

    private static final ChainWriter out=new ChainWriter(System.out);

    private static String root = "/distro";

    /**
     * HashMap of Integer->HashMap(String filename, Boolean wasFound).
     */
    private static Map<Integer,Map<String,Boolean>>
        sortedConfigs,
        sortedNevers,
        sortedNo_recurses,
        sortedOptionals,
        sortedUsers
    ;
    
    private static boolean isConfig(OSFilename osFilename) throws IOException {
        Profiler.startProfile(Profiler.FAST, DistroGenerator.class, "isConfig(OSFilename)", null);
        try {
	    synchronized(DistroGenerator.class) {
		if(sortedConfigs==null) sortedConfigs=loadFileList("configs");
		return containsFile(sortedConfigs, osFilename);
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static Map<Integer,Map<String,Boolean>> getNevers() throws IOException {
        Profiler.startProfile(Profiler.FAST, DistroGenerator.class, "getNevers()", null);
        try {
	    synchronized(DistroGenerator.class) {
		if(sortedNevers==null) sortedNevers=loadFileList("nevers");
                return sortedNevers;
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static boolean isNever(OSFilename osFilename) throws IOException {
        Profiler.startProfile(Profiler.FAST, DistroGenerator.class, "isNever(OSFilename)", null);
        try {
            return containsFile(getNevers(), osFilename);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static boolean isNoRecurse(OSFilename osFilename) throws IOException {
        Profiler.startProfile(Profiler.FAST, DistroGenerator.class, "isNoRecurse(OSFilename)", null);
        try {
            if(osFilename.operating_system_version==-1 || osFilename.filename==null) return false;
	    synchronized(DistroGenerator.class) {
		if(sortedNo_recurses==null) sortedNo_recurses=loadFileList("no_recurses");
		return containsFile(sortedNo_recurses, osFilename);
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static boolean isOptional(OSFilename osFilename) throws IOException {
        Profiler.startProfile(Profiler.FAST, DistroGenerator.class, "isOptional(OSFilename)", null);
        try {
	    synchronized(DistroGenerator.class) {
		if(sortedOptionals==null) sortedOptionals=loadFileList("optionals");
		return containsFile(sortedOptionals, osFilename);
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static boolean isUser(OSFilename osFilename) throws IOException {
        Profiler.startProfile(Profiler.FAST, DistroGenerator.class, "isUser(OSFilename)", null);
        try {
            if(osFilename.operating_system_version==-1 || osFilename.filename==null) return false;
            synchronized(DistroGenerator.class) {
		if(sortedUsers==null) sortedUsers=loadFileList("users");
		return containsFile(sortedUsers, osFilename);
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static Map<Integer,Map<String,Boolean>> loadFileList(String name) throws IOException {
        Profiler.startProfile(Profiler.IO, DistroGenerator.class, "loadFileList(String)", null);
        try {
	    Map<Integer,Map<String,Boolean>> osVersions=new HashMap<Integer,Map<String,Boolean>>();
            BufferedReader in=new BufferedReader(new InputStreamReader(DistroGenerator.class.getResourceAsStream(name+".txt")));
	    try {
		String line;
		while((line=in.readLine())!=null) {
		    int pos1=line.indexOf(',');
		    if(pos1==-1) throw new IOException("cannot find first comma (,) in distro file named "+name+", line="+line);
		    int pos2=line.indexOf(',', pos1+1);
		    if(pos2==-1) throw new IOException("cannot find second comma (,) in distro file named "+name+", line="+line);
		    int pos3=line.indexOf(',', pos2+1);
		    if(pos3==-1) throw new IOException("cannot find third comma (,) in distro file named "+name+", line="+line);
		    int osVersion=getOperatingSystemVersion(
                        line.substring(0, pos1),
                        line.substring(pos1+1, pos2),
                        line.substring(pos2+1, pos3)
                    );
		    String filename=line.substring(pos3+1);
		    Integer I=Integer.valueOf(osVersion);
		    Map<String,Boolean> filenames=osVersions.get(I);
		    if(filenames==null) osVersions.put(I, filenames=new HashMap<String,Boolean>());
		    filenames.put(filename, Boolean.FALSE);
		}
	    } finally {
		in.close();
	    }
            return osVersions;
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    private static boolean containsFile(Map<Integer,Map<String,Boolean>> osVersions, OSFilename osFilename) throws IOException {
        Profiler.startProfile(Profiler.FAST, DistroGenerator.class, "containsFile(Map<Integer,Map<String,Boolean>>,OSFilename)", null);
        try {
            Integer I=Integer.valueOf(osFilename.operating_system_version);
            Map<String,Boolean> filenames=osVersions.get(I);
            if(filenames==null) return false;
            Boolean B=filenames.get(osFilename.filename);
            if(B==null) return false;
            if(B.equals(Boolean.FALSE)) filenames.put(osFilename.filename, Boolean.TRUE);
            return true;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static Map<Integer,Map<Integer,String>> usernames;
    private static String getUsername(OSFilename osFilename, int fileUID) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, DistroGenerator.class, "getUsername(OSFilename,int)", null);
        try {
	    synchronized(DistroGenerator.class) {
		if (usernames==null) usernames=new HashMap<Integer,Map<Integer,String>>();
                Integer I=Integer.valueOf(osFilename.operating_system_version);
                Map<Integer,String> realNames=usernames.get(I);
                if(realNames==null) {
                    usernames.put(I, realNames=new HashMap<Integer,String>());
		    BufferedReader in = new BufferedReader(
                        new InputStreamReader(
                            new BufferedInputStream(
                                new FileInputStream(
                                    new UnixFile(
                                        root+'/'+osFilename.getOSName()+'/'+osFilename.getOSVersion()+'/'+osFilename.getOSArchitecture()+"/etc/passwd"
                                    ).getFile()
                                )
                            )
                        )
                    );
		    try {
			String line;
			while ((line=in.readLine())!=null) {
			    String[] fields = StringUtility.splitString(line, ':');
			    String username = fields[0];
			    Integer userID = Integer.valueOf(Integer.parseInt(fields[2]));
			    if(!realNames.containsKey(userID)) realNames.put(userID, username);
			}
		    } finally {
			in.close();
		    }
		}
		String username=realNames.get(Integer.valueOf(fileUID));
                if(username==null) throw new IOException("Unable to find username: "+fileUID);
                return username;
	    }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    private static Map<Integer,Map<Integer,String>> groupnames;
    private static String getGroupname(OSFilename osFilename, int fileGID) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, DistroGenerator.class, "getGroupname(OSFilename,int)", null);
        try {
	    synchronized(DistroGenerator.class) {
		if (groupnames==null) groupnames=new HashMap<Integer,Map<Integer,String>>();
                Integer I=Integer.valueOf(osFilename.operating_system_version);
                Map<Integer,String> realGroups=groupnames.get(I);
                if(realGroups==null) {
		    groupnames.put(I, realGroups=new HashMap<Integer,String>());
		    BufferedReader in = new BufferedReader(
                        new InputStreamReader(
                            new BufferedInputStream(
                                new FileInputStream(
                                    new UnixFile(
                                        root+'/'+osFilename.getOSName()+'/'+osFilename.getOSVersion()+'/'+osFilename.getOSArchitecture()+"/etc/group"
                                    ).getFile()
                                )
                            )
                        )
                    );
		    try {
			String line;
			while ((line=in.readLine())!=null) {
			    String[] fields = StringUtility.splitString(line, ':');
			    String groupname = fields[0];
			    Integer groupID = Integer.valueOf(Integer.parseInt(fields[2]));
			    if(!realGroups.containsKey(groupID)) realGroups.put(groupID, groupname);
			}
		    } finally {
			in.close();
		    }
		}
		String groupname=realGroups.get(Integer.valueOf(fileGID));
                if(groupname==null) throw new IOException("Unable to find group name: "+fileGID);
                return groupname;
	    }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void main(String[] args) {
        Profiler.startProfile(Profiler.UNKNOWN, DistroGenerator.class, "main(String[])", null);
        try {
            try {
                if (args.length>0) root = args[0];

                // First do a quick scan for nevers
                boolean foundNever=false;
                Map<Integer,Map<String,Boolean>> nevers=getNevers();
                Iterator<Integer> operatingSystems=nevers.keySet().iterator();
                while(operatingSystems.hasNext()) {
                    Integer OSV=operatingSystems.next();
                    int osv=OSV.intValue();
                    Iterator<String> filenames=nevers.get(OSV).keySet().iterator();
                    while(filenames.hasNext()) {
                        String filename=getOperatingSystemPath(osv)+filenames.next();
                        UnixFile uf=new UnixFile(filename);
                        if(uf.exists()) {
                            System.err.println("File exists but is listed in nevers: "+filename);
                            foundNever=true;
                        }
                    }
                }
                if(foundNever) {
                    System.exit(2);
                } else {
                    out.print("create temp table distro_files_tmp (\n"
                            + "  pkey integer\n"
                            + "    not null,\n"
                            + "  operating_system_version integer\n"
                            + "    not null,\n"
                            + "  path text\n"
                            + "    not null,\n"
                            + "  optional bool\n"
                            + "    not null,\n"
                            + "  type text\n"
                            + "    not null,\n"
                            + "  mode int8\n"
                            + "    not null,\n"
                            + "  linux_account text\n"
                            + "    not null,\n"
                            + "  linux_group text\n"
                            + "    not null,\n"
                            + "  size int8,\n"
                            + "  file_md5_hi int8,\n"
                            + "  file_md5_lo int8,\n"
                            + "  symlink_target text\n"
                            + ") without oids;\n"
                            + "begin;\n");
                    synchronized(DistroGenerator.class) {
                        for(int c=0;c<NUM_THREADS;c++) generators[c]=new DistroGenerator(c);
                        activeGeneratorCount=NUM_THREADS;
                        for(int c=0;c<NUM_THREADS;c++) generators[c].start();
                    }
                }
            } catch(IOException err) {
                ErrorPrinter.printStackTraces(err);
                System.exit(1);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final Object nextFilenameLock=new Object();

    private static Stack<String> currentDirectories;
    private static Stack<String[]> currentLists;
    private static Stack<Integer> currentIndexes;

    private static boolean done=false;
    private String getNextFilename(OSFilename temp) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, DistroGenerator.class, "getNextFilename(OSFilename)", null);
        try {
            synchronized(nextFilenameLock) {
                String filename;

                if(done) filename=null;
                else {
                    // Initialize the stacks, if needed
                    if(currentDirectories==null) {
                        (currentDirectories=new Stack<String>()).push("");
                        (currentLists=new Stack<String[]>()).push(new String[] {""});
                        (currentIndexes=new Stack<Integer>()).push(Integer.valueOf(0));
                    }
                }
                String currentDirectory=null;
                String[] currentList=null;
                int currentIndex=-1;
                try {
                    currentDirectory=currentDirectories.peek();
                    currentList=currentLists.peek();
                    currentIndex=currentIndexes.peek().intValue();

                    // Undo the stack as far as needed
                    while(currentDirectory!=null && currentIndex>=currentList.length) {
                        currentDirectories.pop();
                        currentDirectory=currentDirectories.peek();
                        currentLists.pop();
                        currentList=currentLists.peek();
                        currentIndexes.pop();
                        currentIndex=currentIndexes.peek().intValue();
                    }
                } catch(EmptyStackException err) {
                    currentDirectory=null;
                }
                if(currentDirectory==null) {
                    filename=null;
                    done=true;
                } else {
                    // Get the current filename
                    if(currentDirectory.equals("/")) filename="/"+currentList[currentIndex++];
                    else filename=currentDirectory+'/'+currentList[currentIndex++];

                    // Set to the next file
                    currentIndexes.pop();
                    currentIndexes.push(Integer.valueOf(currentIndex));

                    // Recurse for directories
                    try {
                        UnixFile unixFile=new UnixFile(root+filename);
                        long statMode=unixFile.getStatMode();
                        temp.parseValues(filename);
                        if(
                            !UnixFile.isSymLink(statMode)
                            && UnixFile.isDirectory(statMode)
                            && !isUser(temp)
                            && !isNoRecurse(temp)
                        ) {
                            // Push on stacks for next level
                            currentDirectories.push(filename);
                            String[] list=unixFile.list();
                            if(list==null) list=new String[0];
                            AutoSort.sortStatic(list);
                            currentLists.push(list);
                            currentIndexes.push(Integer.valueOf(0));
                        }
                    } catch(FileNotFoundException err) {
                        System.err.println("Error trying to access file: "+root+filename);
                        throw err;
                    }
                }
                return filename;
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final Object outputLock=new Object();
    private static int outputCount=0;
    private static boolean isFirstOutput=true;
    private void print(String line1) {
        Profiler.startProfile(Profiler.UNKNOWN, DistroGenerator.class, "print(String)", null);
        try {
            synchronized(outputLock) {
                if(outputCount==0) {
                    if(isFirstOutput) isFirstOutput=false;
                    else out.print("begin;\n");
                }
                out.print(line1).print('\n');
                if(++outputCount>=ROWS_PER_TRANSACTION) {
                    out.print("commit;\n");
                    outputCount=0;
                }
                out.flush();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    final private int threadNum;

    private DistroGenerator(int threadNum) {
        Profiler.startProfile(Profiler.INSTANTANEOUS, DistroGenerator.class, "<init>(int)", null);
        try {
            this.threadNum=threadNum;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, DistroGenerator.class, "run()", null);
        try {
            try {
                final OSFilename osFilename=new OSFilename();
                while(true) {
                    String filename=getNextFilename(osFilename);
                    if(filename==null) break;
                    osFilename.parseValues(filename);
                    if(osFilename.operating_system_version!=-1 && osFilename.filename!=null) {
                        if(isNever(osFilename)) {
                            System.err.println("Found a file in the distribution template that should never exist: "+osFilename);
                            System.exit(1);
                        }
                        // Determine the type
                        String type=isUser(osFilename)?DistroFileType.USER
                            :isConfig(osFilename)?DistroFileType.CONFIG
                            :isNoRecurse(osFilename)?DistroFileType.NO_RECURSE
                            :DistroFileType.SYSTEM
                        ;

                        // Decide if the size should be stored
                        UnixFile file = new UnixFile(osFilename.getFullPath(root));
                        long statMode=file.getStatMode();
                        boolean isRegularFile=UnixFile.isRegularFile(statMode);
                        boolean storeSize=isRegularFile && !isConfig(osFilename);

                        // Only hash system regular files
                        boolean doHash=isRegularFile && type.equals(DistroFileType.SYSTEM);

                        try {
                            StringBuilder SB=new StringBuilder();
                            SB
                                .append("insert into distro_files_tmp values (nextval('distro_files_pkey_seq'), ")
                                .append(osFilename.operating_system_version)
                                .append(", '")
                                .append(SQLUtility.escapeSQL(osFilename.filename.length()==0?"/":osFilename.filename))
                                .append("', ")
                                .append(isOptional(osFilename)?"true":"false")
                                .append(", '")
                                .append(type)
                                .append("', ")
                                .append(statMode)
                                .append("::int8, '")
                                .append(SQLUtility.escapeSQL(getUsername(osFilename, file.getUID())))
                                .append("', '")
                                .append(SQLUtility.escapeSQL(getGroupname(osFilename, file.getGID())))
                                .append("', ");
                            if(storeSize) SB.append(file.getSize()).append("::int8");
                            else SB.append("null");
                            SB.append(", ");
                            if(doHash) {
                                byte[] md5=DistroManager.hashFile(osFilename.getFullPath(root));
                                SB.append(MD5.getMD5Hi(md5)).append("::int8, ").append(MD5.getMD5Lo(md5)).append("::int8");
                            } else SB.append("null, null");
                            SB.append(", ");
                            if(UnixFile.isSymLink(statMode)) SB.append('\'').append(SQLUtility.escapeSQL(file.readLink())).append('\'');
                            else SB.append("null");
                            SB.append(");");
                            print(SB.toString());
                        } catch(IOException err) {
                            System.err.println("Error on file: "+osFilename.getFullPath(root));
                            throw err;
                        }
                    }
                }

                // Thread done
                synchronized(DistroGenerator.class) {
                    generators[threadNum]=null;
                    activeGeneratorCount--;
                    if(activeGeneratorCount<=0) {
                        // Finish the program
                        if(outputCount>0) out.print("commit;\n");
                        out.print("select\n"
                                + "  dft.*\n"
                                + "from\n"
                                + "  distro_files_tmp dft\n"
                                + "  left outer join linux_accounts la on dft.linux_account=la.username\n"
                                + "  left outer join linux_groups lg on dft.linux_group=lg.name\n"
                                + "where\n"
                                + "  la.username is null\n"
                                + "  or lg.name is null\n"
                                + ";\n"
                                + "begin;\n"
                                + "delete from distro_files;\n"
                                + "insert into distro_files select * from distro_files_tmp;\n"
                                + "commit;\n"
                                + "drop table distro_files_tmp;\n"
                                + "vacuum full analyze distro_files;\n");
                        out.flush();

                        // Report files that in are configs but now found in template
                        reportMissingTemplateFiles("configs.txt", sortedConfigs);
                        reportMissingTemplateFiles("no_recurses.txt", sortedNo_recurses);
                        reportMissingTemplateFiles("optionals.txt", sortedOptionals);
                        reportMissingTemplateFiles("users.txt", sortedUsers);
                    }
                }
            } catch(IOException err) {
                ErrorPrinter.printStackTraces(err);
                System.exit(1);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    public static int getOperatingSystemVersion(String name, String version, String architecture) {
        if(
            name.equals(OperatingSystem.MANDRAKE)
            && version.equals(OperatingSystemVersion.VERSION_10_1)
            && architecture.equals(Architecture.I586)
        ) return OperatingSystemVersion.MANDRAKE_10_1_I586;
        if(
            name.equals(OperatingSystem.MANDRIVA)
            && version.equals(OperatingSystemVersion.VERSION_2006_0)
            && architecture.equals(Architecture.I586)
        ) return OperatingSystemVersion.MANDRIVA_2006_0_I586;
        if(
            name.equals(OperatingSystem.REDHAT)
            && version.equals(OperatingSystemVersion.VERSION_ES_4)
            && architecture.equals(Architecture.X86_64)
        ) return OperatingSystemVersion.REDHAT_ES_4_X86_64;
        throw new RuntimeException("Unsupported operating system: name="+name+", version="+version+", architecture="+architecture);
    }
    
    public static void reportMissingTemplateFiles(String filename, Map<Integer,Map<String,Boolean>> lists) {
        boolean reportedOne=false;
        List<Integer> osvs=sort(lists.keySet().iterator());
        for(int c=0;c<osvs.size();c++) {
            Integer OSV=osvs.get(c);
            int osv=OSV.intValue();
            Map<String,Boolean> filenameMap=lists.get(OSV);
            List<String> filenames=sort(filenameMap.keySet().iterator());
            for(int d=0;d<filenames.size();d++) {
                String path=(String)filenames.get(d);
                boolean found=((Boolean)filenameMap.get(path)).booleanValue();
                if(!found) {
                    if(!reportedOne) {
                        reportedOne=true;
                        System.err.println();
                        System.err.println("*************************************************************************");
                        System.err.print("* WARNING: These files are listed in ");
                        System.err.println(filename);
                        System.err.println("* but not found in the distribution template");
                        System.err.println("*************************************************************************");
                    }
                    if(osv==OperatingSystemVersion.MANDRAKE_10_1_I586) System.err.print("mandrake,10.1,i586,");
                    else if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) System.err.print("mandriva,2006.0,i586,");
                    else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) System.err.print("redhat,ES 4,x86_64,");
                    else throw new RuntimeException("Unknown value for osv: "+osv);
                    System.err.println(path);
                }
            }
        }
    }
    
    private static String getOperatingSystemPath(int osv) {
        if(osv==OperatingSystemVersion.MANDRAKE_10_1_I586) return root+"/mandrake/10.1/i586";
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) return root+"/mandriva/2006.0/i586";
        if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) return root+"/redhat/ES 4/x86_64";
        else throw new RuntimeException("Unknown value for osv: "+osv);
    }

    private static <T> List<T> sort(Iterator<T> I) {
        List<T> list=new ArrayList<T>();
        while(I.hasNext()) list.add(I.next());
        AutoSort.sortStatic(list);
        return list;
    }
}
