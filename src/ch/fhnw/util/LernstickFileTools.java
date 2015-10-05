package ch.fhnw.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * some file tools
 *
 * @author Ronny Standtke <ronny.standtke@fhnw.ch>
 */
public class LernstickFileTools {

    private static final Logger LOGGER
            = Logger.getLogger(LernstickFileTools.class.getName());
    private static final ResourceBundle STRINGS
            = ResourceBundle.getBundle("ch/fhnw/util/Strings");
    private final static NumberFormat NUMBER_FORMAT
            = NumberFormat.getInstance();

    /**
     * reads a file line by line
     *
     * @param file the file to read
     * @return the list of lines in this file
     * @throws IOException if an I/O exception occurs
     */
    public static List<String> readFile(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            for (String line = reader.readLine(); line != null;) {
                lines.add(line);
                line = reader.readLine();
            }
        }
        return lines;
    }

    /**
     * returns the string representation of a given data volume
     *
     * @param bytes the datavolume given in Byte
     * @param fractionDigits the number of fraction digits to display
     * @return the string representation of a given data volume
     */
    public static String getDataVolumeString(long bytes, int fractionDigits) {
        if (bytes >= 1024) {
            NUMBER_FORMAT.setMaximumFractionDigits(fractionDigits);
            float kbytes = (float) bytes / 1024;
            if (kbytes >= 1024) {
                float mbytes = (float) bytes / 1048576;
                if (mbytes >= 1024) {
                    float gbytes = (float) bytes / 1073741824;
                    if (gbytes >= 1024) {
                        float tbytes = (float) bytes / 1099511627776L;
                        return NUMBER_FORMAT.format(tbytes) + " TiB";
                    }
                    return NUMBER_FORMAT.format(gbytes) + " GiB";
                }

                return NUMBER_FORMAT.format(mbytes) + " MiB";
            }

            return NUMBER_FORMAT.format(kbytes) + " KiB";
        }

        return NUMBER_FORMAT.format(bytes) + " Byte";
    }

    /**
     * creates a temporary directory
     *
     * @param parentDir the parent directory
     * @param name the name of the temporary directory
     * @return the temporary directory
     */
    public static File createTempDirectory(File parentDir, String name) {
        File tempDir = new File(parentDir, name);
        if (tempDir.exists()) {
            // search for an alternative non-existing directory
            for (int i = 1;
                    (tempDir = new File(parentDir, name + i)).exists(); i++) {
            }
        }
        if (!tempDir.mkdirs()) {
            LOGGER.log(Level.WARNING, "can not create {0}", tempDir);
        }
        return tempDir;
    }

    /**
     * recusively deletes a file
     *
     * @param file the file to delete
     * @param removeFile if the file (directory) itself should be removed or
     * just its subfiles
     * @return <code>true</code> if and only if the file or directory is
     * successfully deleted; <code>false</code> otherwise
     * @throws IOException if an I/O exception occurs
     */
    public static boolean recursiveDelete(File file, boolean removeFile)
            throws IOException {
        // do NOT(!) follow symlinks when deleting files
        if (file.isDirectory() && !isSymlink(file)) {
            File[] subFiles = file.listFiles();
            if (subFiles != null) {
                for (File subFile : subFiles) {
                    recursiveDelete(subFile, true);
                }
            }
        }
        return removeFile ? file.delete() : true;
    }

    /**
     * checks if a file is a symlink
     *
     * @param file the file to check
     * @return <tt>true</tt>, if <tt>file</tt> is a symlink, <tt>false</tt>
     * otherwise
     * @throws IOException if an I/O exception occurs
     */
    public static boolean isSymlink(File file) throws IOException {
        File canon;
        if (file.getParent() == null) {
            canon = file;
        } else {
            File canonDir = file.getParentFile().getCanonicalFile();
            canon = new File(canonDir, file.getName());
        }
        return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
    }

    /**
     * mounts all squashfs found in a given systemPath
     *
     * @param systemPath the given systemPath
     * @return a list of mount points
     * @throws IOException
     */
    public static List<String> mountAllSquashFS(String systemPath)
            throws IOException {
        // get a list of all available squashfs
        FilenameFilter squashFsFilter = new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".squashfs");
            }
        };
        File liveDir = new File(systemPath, "live");
        File[] squashFileSystems = liveDir.listFiles(squashFsFilter);

        // mount all squashfs read-only in temporary directories
        List<String> readOnlyMountPoints = new ArrayList<>();
        Path tmpDir = Files.createTempDirectory(null);
        ProcessExecutor processExecutor = new ProcessExecutor();
        for (int i = 0; i < squashFileSystems.length; i++) {
            Path roDir = tmpDir.resolve("ro" + (i + 1));
            Files.createDirectory(roDir);
            String roPath = roDir.toString();
            readOnlyMountPoints.add(roPath);
            String filePath = squashFileSystems[i].getPath();
            processExecutor.executeProcess(
                    "mount", "-o", "loop", filePath, roPath);
        }
        return readOnlyMountPoints;
    }

    /**
     * mounts an aufs file system with the given branch definition
     *
     * @param branchDefinition the given branch definition
     * @return the mount point
     * @throws IOException
     */
    public static File mountAufs(String branchDefinition) throws IOException {
        // cowDir is placed in /run/ because it is one of
        // the few directories that are not aufs itself.
        // Nested aufs is not (yet) supported...
        File runDir = new File("/run/");

        // To create the file system union, we need a temporary and
        // writable xino file that must not reside in an aufs. Therefore
        // we use a file in the /run directory, which is a writable
        // tmpfs.
        File xinoTmpFile = File.createTempFile(".aufs.xino", "", runDir);
        xinoTmpFile.delete();

        File cowDir = createTempDirectory(runDir, "cow");
        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.executeProcess("mount", "-t", "aufs",
                "-o", "xino=" + xinoTmpFile.getPath(),
                "-o", branchDefinition, "none", cowDir.getPath());
        return cowDir;
    }

    /**
     * creates an aufs branch definition for a read-write mount point and a list
     * of read-only mountpoints
     *
     * @param readWriteMountPoint the read-write mount point
     * @param readOnlyMountPoints the list of read-only partitions
     * @return
     */
    public static String getBranchDefinition(
            String readWriteMountPoint, List<String> readOnlyMountPoints) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("br=");
        stringBuilder.append(readWriteMountPoint);
        for (String readOnlyMountPoint : readOnlyMountPoints) {
            stringBuilder.append(':');
            stringBuilder.append(readOnlyMountPoint);
        }
        return stringBuilder.toString();
    }

    static long getSize(Path path) throws IOException {
        final AtomicLong size = new AtomicLong(0);

        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attrs) throws IOException {
                size.addAndGet(attrs.size());
                return FileVisitResult.CONTINUE;
            }
        });

        return size.get();
    }

    /**
     * unmounts a device or mountpoint
     *
     * @param deviceOrMountpoint the device or mountpoint to unmount
     * @throws IOException
     */
    public static void umount(String deviceOrMountpoint) throws IOException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        int exitValue = processExecutor.executeProcess(
                "umount", deviceOrMountpoint);
        if (exitValue != 0) {
            String errorMessage = STRINGS.getString("Error_Umount");
            errorMessage = MessageFormat.format(
                    errorMessage, deviceOrMountpoint);
            LOGGER.severe(errorMessage);
            throw new IOException(errorMessage);
        }
    }

}
