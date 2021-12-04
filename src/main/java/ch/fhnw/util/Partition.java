package ch.fhnw.util;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.udisks.Device;
import org.xml.sax.SAXException;

/**
 * A storage device partition
 *
 * @author Ronny Standtke <ronny.standtke@fhnw.ch>
 */
public class Partition {

    /**
     * the label used for efi partitions
     */
    public final static String EFI_LABEL = "EFI";
    /**
     * the label used for persistence partitions
     */
    public final static String PERSISTENCE_LABEL = "persistence";
    private final static String[] LEGACY_PERSISTENCE_LABELS = new String[]{
        "live-rw"
    };
    private final static String[] LEGACY_EFI_LABELS = new String[]{
        "boot"
    };
    private final static Logger LOGGER
            = Logger.getLogger(Partition.class.getName());
    private final static Pattern DEVICE_AND_NUMBER_PATTERN
            = Pattern.compile("(.*)(\\d+)");
    private final static Pattern DEVICE_P_AND_NUMBER_PATTERN
            = Pattern.compile("(.*)(p\\d+)");
    private final static String CRYPTSETUP
            = "cryptsetup --pbkdf pbkdf2 --pbkdf-force-iterations 1000 ";
    private final String device;
    private final int number;
    private final String deviceAndNumber;
    private final long offset;
    private final long size;
    private final String type;
    private final String idLabel;
    private final String idType;
    private final boolean isDrive;
    private Boolean isBootPartition;
    private Boolean isSystemPartition;
    private Long usedSpace;
    private StorageDevice storageDevice;

    /**
     * creates a new Partition
     *
     * @param deviceAndNumber the device of the partition including the
     * numberString (e.g. "sda1" or "nvme0n1p1")
     * @return a new Partition
     * @throws DBusException if getting the partition properties via dbus fails
     */
    public static Partition getPartitionFromDeviceAndNumber(
            String deviceAndNumber) throws DBusException {
        LOGGER.log(Level.FINE, "deviceAndNumber: \"{0}\"", deviceAndNumber);
        Matcher matcher;
        if (deviceAndNumber.startsWith("mmcblk")
                || deviceAndNumber.startsWith("nvme")) {
            matcher = DEVICE_P_AND_NUMBER_PATTERN.matcher(deviceAndNumber);
        } else {
            matcher = DEVICE_AND_NUMBER_PATTERN.matcher(deviceAndNumber);
        }
        if (matcher.matches()) {
            return getPartitionFromDevice(matcher.group(1), matcher.group(2));
        }
        return null;
    }

    /**
     * creates a new Partition
     *
     * @param mountPoint the mount point of the partition
     * @return a new Partition
     * @throws DBusException if getting the partition properties via dbus fails
     * @throws IOException if reading "/proc/mounts" fails
     */
    public static Partition getPartitionFromMountPoint(String mountPoint)
            throws DBusException, IOException {
        LOGGER.log(Level.FINE, "mountPoint: \"{0}\"", mountPoint);
        List<String> mounts
                = LernstickFileTools.readFile(new File("/proc/mounts"));
        for (String mount : mounts) {
            String[] tokens = mount.split(" ");
            if (tokens[0].startsWith("/dev/") && tokens[1].equals(mountPoint)) {
                return getPartitionFromDeviceAndNumber(tokens[0].substring(5));
            }
        }
        return null;
    }

    /**
     * creates a new Partition
     *
     * @param device the device of the partition (e.g. "sda" or "nvme0n1")
     * @param numberString the numberString string of the partition (e.g. "1" or
     * "p1")
     * @return a String representation of the partition numberString
     * @throws DBusException if getting the partition properties via dbus fails
     */
    public static Partition getPartitionFromDevice(
            String device, String numberString) throws DBusException {

        LOGGER.log(Level.FINE, "\n"
                + "    thread: {0}\n"
                + "    device: \"{1}\"\n"
                + "    numberString: \"{2}\"",
                new Object[]{Thread.currentThread().getName(),
                    device, numberString});

        // for newer D-BUS versions we have to check, if this device is a
        // partiton at all
        if ((DbusTools.DBUS_VERSION == DbusTools.DbusVersion.V1)
                || (DbusTools.isPartition(device + numberString))) {
            return new Partition(device, numberString);
        } else {
            return null;
        }
    }

    /**
     * sets the boot flag on the partition (via parted)
     *
     * @param enabled
     * @throws DBusException if a D-BUS exception occurs
     * @throws java.io.IOException if running parted failed
     */
    public void setBootFlag(boolean enabled) throws DBusException, IOException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        int returnValue = processExecutor.executeProcess(true, true, "parted",
                "/dev/" + getStorageDevice().getDevice(), "set",
                String.valueOf(getNumber()), "boot", enabled ? "on" : "off");
        if (returnValue != 0) {
            throw new IOException("could not change boot flag on partition "
                    + toString());
        }
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("/dev/");
        stringBuilder.append(deviceAndNumber);
        stringBuilder.append(", offset: ");
        stringBuilder.append(offset);
        stringBuilder.append(", size: ");
        stringBuilder.append(size);
        if ((idLabel != null) && !idLabel.isEmpty()) {
            stringBuilder.append(", idLabel: \"");
            stringBuilder.append(idLabel);
            stringBuilder.append('\"');
        }
        if ((idType != null) && !idType.isEmpty()) {
            stringBuilder.append(", idType: \"");
            stringBuilder.append(idType);
            stringBuilder.append('\"');
        }
        if ((type != null) && !type.isEmpty()) {
            stringBuilder.append(", type: \"");
            stringBuilder.append(type);
            stringBuilder.append('\"');
        }
        return stringBuilder.toString();
    }

    /**
     * makes sure that the parititon is mounted and then executes an Action
     *
     * @param <T> the return type of the execution
     * @param action the action to execute
     * @return the return value of the action
     * @throws DBusException if a D-BUS exception occurs
     * @throws java.io.IOException if an I/O exception occurs
     */
    public <T> T executeMounted(Action<T> action)
            throws DBusException, IOException {

        // make sure partition is mounted before executing the action
        MountInfo mountInfo = null;
        if (!isMounted()) {
            mountInfo = mount();
        }
        File mountPath = new File(getMountPath());

        // execute generic action
        T t = action.execute(mountPath);

        // cleanup
        if ((mountInfo != null) && (!mountInfo.alreadyMounted())) {
            umount();
        }
        return t;
    }

    /**
     * a generic action that can be executed when the partition is mounted
     *
     * @param <T> the return type of the execute function
     */
    public static abstract class Action<T> {

        /**
         * executed this action
         *
         * @param mountPath the path where the partition is mounted
         * @return the return value
         */
        public abstract T execute(File mountPath);
    }

    /**
     * returns the StorageDevice of this Partition
     *
     * @return the StorageDevice of this Partition
     * @throws DBusException if getting the StorageDevice properties via d-bus
     * fails
     */
    public synchronized StorageDevice getStorageDevice() throws DBusException {
        if (storageDevice == null) {
            storageDevice = new StorageDevice(
                    isDrive ? deviceAndNumber : device);
        }
        return storageDevice;
    }

    /**
     * returns the partition number
     *
     * @return the partition number
     */
    public int getNumber() {
        return number;
    }

    /**
     * Returns the device and numberString of this partition, e.g. "sda1" or
     * "nvme0n1p1".
     *
     * @return the device and numberString of this partition, e.g. "sda1" or
     * "nvme0n1p1"
     */
    public String getDeviceAndNumber() {
        return deviceAndNumber;
    }

    /**
     * Returns the full device and numberString of this partition, e.g.
     * "/dev/sda1" or "/dev/nvme0n1p1".
     *
     * @return the full device and numberString of this partition, e.g.
     * "/dev/sda1" or "/dev/nvme0n1p1".
     */
    public String getFullDeviceAndNumber() {
        return "/dev/" + deviceAndNumber;
    }

    /**
     * returns the offset (start) of the partition
     *
     * @return the offset (start) of the partition
     */
    public long getOffset() {
        return offset;
    }

    /**
     * returns the size of this partition
     *
     * @return the size of this partition
     */
    public long getSize() {
        return size;
    }

    /**
     * returns the label of the partition
     *
     * @return the label of the partition
     */
    public String getIdLabel() {
        return idLabel;
    }

    /**
     * returns the ID type of the partition, e.g. "vfat"
     *
     * @return the ID type of the partition, e.g. "vfat"
     */
    public String getIdType() {
        return idType;
    }

    /**
     * returns the partition type, e.g. "0x83" for Linux partitions
     *
     * @return the partition type, e.g. "0x83" for Linux partitions
     */
    public String getType() {
        return type;
    }

    /**
     * returns a list of mount paths of this partition
     *
     * @return a list of mount paths of this partition
     * @throws DBusException if a D-BUS exception occurs
     * @throws java.io.IOException if an I/O exception occurs
     */
    public List<String> getMountPaths() throws DBusException, IOException {

        if (DbusTools.DBUS_VERSION == DbusTools.DbusVersion.V1) {

            return DbusTools.getStringListProperty(
                    deviceAndNumber, "DeviceMountPaths");

        } else {

            List<List> mountPaths = DbusTools.getListListProperty(
                    getObjectPath(), "org.freedesktop.UDisks2.Filesystem",
                    "MountPoints");
            List<String> resultList = new ArrayList<>();
            for (List mountPath : mountPaths) {
                // ignore terminating 0 (therefore "- 1")
                byte[] asBytes = new byte[mountPath.size() - 1];
                for (int i = 0; i < asBytes.length; i++) {
                    asBytes[i] = (byte) mountPath.get(i);
                }
                resultList.add(new String(asBytes));
            }
            return resultList;
        }
    }

    /**
     * returns the first mount path of this partition
     *
     * @return the first mount path of this partition
     * @throws DBusException if a D-BUS exception occurs
     * @throws java.io.IOException
     */
    public String getMountPath() throws DBusException, IOException {
        List<String> mountPaths = getMountPaths();
        if (mountPaths.isEmpty()) {
            return null;
        }
        return mountPaths.get(0);
    }

    /**
     * checks if the partition is an extended partition
     *
     * @return <code>true</code>, if the partition is an extended partition,
     * <code>false</code> otherwise
     */
    public boolean isExtended() {
        return type.equals("0x05") || type.equals("0x0f");
    }

    /**
     * checks if the file system on the partition is ext[2|3|4]
     *
     * @return <code>true</code>, if the file system on the partition is
     * ext[2|3|4], <code>false</code> otherwise
     */
    public boolean hasExtendedFilesystem() {
        return idType.equals("ext2")
                || idType.equals("ext3")
                || idType.equals("ext4");
    }

    /**
     * returns the used space on this partition
     *
     * @param onlyHomeAndCups calculate space only for "/home/user/" and
     * "/etc/cups/"
     * @return the free/usable space on this partition or "-1" if the usable
     * space is unknown
     */
    public synchronized long getUsedSpace(boolean onlyHomeAndCups) {
        try {
            if (usedSpace == null) {

                // mount partition
                MountInfo mountInfo = mount();
                String mountPath = mountInfo.getMountPath();

                if (mountPath == null) {
                    LOGGER.log(Level.WARNING, "Can''t determine used space of "
                            + "/dev/{0} because it couldn''t be mounted",
                            deviceAndNumber);
                    return -1l;
                }

                if (onlyHomeAndCups) {
                    // in case of an upgrade we would only keep /home/user and
                    // /etc/cups
                    long userSize = 0;
                    long cupsSize = 0;
                    ProcessExecutor processExecutor = new ProcessExecutor();
                    Pattern pattern = Pattern.compile("^(\\d+).*");
                    processExecutor.executeProcess(true, true,
                            "du", "-sb", mountPath + "/home/user");
                    String stdOut = processExecutor.getStdOut();
                    LOGGER.log(Level.INFO, "stdOut = \"{0}\"", stdOut);
                    Matcher matcher = pattern.matcher(stdOut);
                    if (matcher.find()) {
                        String userSizeString = matcher.group(1);
                        userSize = Long.parseLong(userSizeString);
                    }
                    LOGGER.log(Level.INFO, "{0}: userSize = {1}",
                            new Object[]{toString(), userSize});

                    processExecutor.executeProcess(true, true,
                            "du", "-sb", mountPath + "/etc/cups");
                    matcher = pattern.matcher(processExecutor.getStdOut());
                    if (matcher.find()) {
                        String userSizeString = matcher.group(1);
                        cupsSize = Long.parseLong(userSizeString);
                    }
                    LOGGER.log(Level.INFO, "{0}: cupsSize = {1}",
                            new Object[]{toString(), cupsSize});
                    usedSpace = userSize + cupsSize;

                } else {
                    // "size" is the size of the partition, the size of the
                    // file system is a little bit smaller because of the file
                    // system overhead.
                    // Therefore we have to use File.getTotalSpace() instead.
                    File mountPathFile = new File(mountPath);
                    usedSpace = mountPathFile.getTotalSpace()
                            - mountPathFile.getFreeSpace();
                }

                LOGGER.log(Level.INFO, "{0}: usedSpace = {1}",
                        new Object[]{toString(), usedSpace});

                // cleanup
                if (!mountInfo.alreadyMounted()) {
                    umount();
                }
            }
        } catch (DBusExecutionException | DBusException
                | NumberFormatException | IOException ex) {
            LOGGER.log(Level.WARNING, "", ex);
            usedSpace = -1l;
        }

        return usedSpace;
    }

    /**
     * returns the used space of a path on this partition
     *
     * @param path the path to exermine
     * @return the free/usable space of the given path on this partition or "-1"
     * if the usable space is unknown
     */
    public synchronized long getUsedSpace(String path) {

        long pathSpace = 0;

        try {

            // mount partition
            MountInfo mountInfo = mount();
            String mountPath = mountInfo.getMountPath();

            ProcessExecutor processExecutor = new ProcessExecutor();
            Pattern pattern = Pattern.compile("^(\\d+).*");
            processExecutor.executeProcess(true, true,
                    "du", "-sb", mountPath + path);
            String stdOut = processExecutor.getStdOut();
            LOGGER.log(Level.INFO, "stdOut = \"{0}\"", stdOut);
            Matcher matcher = pattern.matcher(stdOut);
            if (matcher.find()) {
                String userSizeString = matcher.group(1);
                pathSpace = Long.parseLong(userSizeString);
            }
            LOGGER.log(Level.INFO, "{0} {1}: pathSpace = {2}",
                    new Object[]{toString(), path, pathSpace});

            // cleanup
            if (!mountInfo.alreadyMounted()) {
                umount();
            }

        } catch (DBusExecutionException | DBusException
                | NumberFormatException | IOException ex) {
            LOGGER.log(Level.WARNING, "", ex);
        }

        return pathSpace;
    }

    /**
     * mounts this partition via dbus/udisks
     *
     * @param options the mount options
     * @return the mount information
     * @throws DBusException if a D-BUS exception occurs
     * @throws java.io.IOException if an I/O exception occurs
     */
    public synchronized MountInfo mount(String... options)
            throws DBusException, IOException {

        try {
            String mountPath = null;
            boolean wasMounted = false;
            List<String> mountPaths = getMountPaths();
            if (mountPaths.isEmpty()) {

                String blockDevice = getBlockDevice();

                if (DbusTools.DBUS_VERSION == DbusTools.DbusVersion.V1) {

                    Device udisksDevice = DbusTools.getDevice(blockDevice);

                    mountPath = udisksDevice.FilesystemMount(
                            "auto", Arrays.asList(options));
                } else {
                    // creating a Java interface for
                    // org.freedestkop.UDisks2.Filesystem fails, see here:
                    // https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=777241
                    //
                    // So we have to call udisksctl directly.
                    // This utterly sucks but our options are limited...

                    ProcessExecutor processExecutor = new ProcessExecutor();
                    int returnValue = processExecutor.executeProcess(
                            true, true, "udisksctl", "mount", "-b",
                            "/dev/" + blockDevice);
                    if (returnValue == 0) {
                        String output = processExecutor.getStdOutList().get(0);
                        // The device can be something like /dev/sda1 for
                        // plaintext devices or something like /dev/dm-0 for
                        // mapped LUKS devices.
                        // Unfortunately, the output is not very stable, in
                        // Debian 10 it reads:
                        // Mounted /dev/sda2 at /media/root/persistence.
                        // In Debian 11 the dot at the end is missing:
                        // Mounted /dev/sda2 at /media/root/persistence
                        if (output.endsWith(".")) {
                            output = output.substring(0, output.length() - 1);
                        }
                        Pattern pattern = Pattern.compile(
                                "Mounted /dev/\\p{Graph}+ at (.*)");
                        Matcher matcher = pattern.matcher(output);
                        if (matcher.matches()) {
                            mountPath = matcher.group(1);
                        }
                    }
                }
            } else {
                mountPath = mountPaths.get(0);
                wasMounted = true;
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "{0} already mounted at {1}",
                            new Object[]{this, mountPath}
                    );
                }
            }
            LOGGER.log(Level.INFO, "mountPath: {0}", mountPath);
            return new MountInfo(mountPath, wasMounted);
        } catch (DBusExecutionException ex) {
            LOGGER.log(Level.WARNING, "", ex);
            throw new DBusException(ex.getMessage());
        }
    }

    /**
     * umounts this partition via dbus/udisks
     *
     * @return <code>true</code>, if the umount operation succeeded,
     * <code>false</code> otherwise
     * @throws DBusException if a D-BUS exception occurs
     * @throws java.io.IOException if an I/O exception occurs
     */
    public synchronized boolean umount() throws DBusException, IOException {
        /**
         * TODO: umount timeout problem: when there have been previous copy
         * operations, this call very often fails with the following exception:
         * org.freedesktop.DBus$Error$NoReply: No reply within specified time at
         * org.freedesktop.dbus.RemoteInvocationHandler.executeRemoteMethod(RemoteInvocationHandler.java:133)
         * at
         * org.freedesktop.dbus.RemoteInvocationHandler.invoke(RemoteInvocationHandler.java:188)
         * at $Proxy2.FilesystemUnmount(Unknown Source)
         */
        String blockDevice = getBlockDevice();
        boolean success = false;
        for (int i = 0; !success && (i < 60); i++) {
            // it already happend that during the timeout
            // in handleUmountException() the umount call succeeded!
            // therefore we need to test for the mount status in every round
            // and act accordingly...

            List<String> mountPaths = getMountPaths();
            if ((mountPaths != null) && (!mountPaths.isEmpty())) {
                LOGGER.log(Level.INFO, "\n"
                        + "    thread: {0}\n"
                        + "    /dev/{1} is mounted on {2}, calling umount...",
                        new Object[]{Thread.currentThread().getName(),
                            blockDevice, mountPaths.get(0)});
                if (DbusTools.DBUS_VERSION == DbusTools.DbusVersion.V1) {
                    try {
                        Device dbusDevice = DbusTools.getDevice(blockDevice);
                        dbusDevice.FilesystemUnmount(new ArrayList<>());
                        success = true;
                    } catch (DBusException | DBusExecutionException ex) {
                        handleUmountException(ex);
                    }
                } else {
                    // creating a Java interface for
                    // org.freedestkop.UDisks2.Filesystem fails, see here:
                    // https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=777241
                    //
                    // So we have to call udisksctl directly.
                    // This utterly sucks but our options are limited...
                    ProcessExecutor processExecutor = new ProcessExecutor();
                    int returnValue = processExecutor.executeProcess(
                            true, true, "udisksctl", "unmount", "-b",
                            "/dev/" + blockDevice);
                    if (returnValue == 0) {
                        success = true;
                    } else {
                        handleUmountException(new Exception("unmount failed"));
                    }
                }
            } else {
                LOGGER.log(Level.INFO, "/dev/{0} was NOT mounted", blockDevice);
                success = true;
            }
        }

        if (success) {
            if (isLuksEncrypted()) {
                ProcessExecutor processExecutor = new ProcessExecutor(true);
                processExecutor.executeProcess(true, true,
                        "cryptsetup", "luksClose", getLUKSMappingName());
            }
        } else {
            LOGGER.log(Level.SEVERE, "Could not umount /dev/{0}", blockDevice);
        }
        return success;
    }

    /**
     * returns <code>true</code>, if this partition is a Lernstick EFI
     * partition, <code>false</code> otherwise
     *
     * @return <code>true</code>, if this partition is a Lernstick EFI
     * partition, <code>false</code> otherwise
     * @throws DBusException if a D-BUS exception occurs
     */
    public synchronized boolean isEfiPartition() throws DBusException {
        if (isBootPartition == null) {
            isBootPartition = false;
            if (EFI_LABEL.equals(idLabel)) {
                isBootPartition = true;
            } else {
                for (String legacyEfiLabel : LEGACY_EFI_LABELS) {
                    if (legacyEfiLabel.equals(idLabel)) {
                        isBootPartition = true;
                        isSystemPartition = false;
                        break;
                    }
                }
            }

            LOGGER.log(Level.FINEST, "\n"
                    + "    thread: {0}\n"
                    + "    checking partition {1}\n"
                    + "    partition label: \"{2}\"\n"
                    + "    --> {3}",
                    new Object[]{
                        Thread.currentThread().getName(),
                        deviceAndNumber, idLabel, isBootPartition
                                ? "matches efi/boot partition label"
                                : "does *NOT* match efi/boot partition label"
                    });
        }
        return isBootPartition;
    }

    /**
     * returns <code>true</code>, if this partition is a Debian Live system
     * partition, <code>false</code> otherwise
     *
     * @return <code>true</code>, if this partition is a Debian Live system
     * partition, <code>false</code> otherwise
     * @throws DBusException if a D-BUS exception occurs
     */
    public synchronized boolean isSystemPartition() throws DBusException {
        if (isSystemPartition == null) {
            isSystemPartition = false;

            LOGGER.log(Level.FINEST, "\n"
                    + "    thread: {0}\n"
                    + "    checking partition {1}\n"
                    + "    partition label: \"{2}\"",
                    new Object[]{Thread.currentThread().getName(),
                        deviceAndNumber, idLabel});

            try {
                // mount partition (if not already mounted)
                MountInfo mountInfo = mount();
                String mountPath = mountInfo.getMountPath();

                // check partition file structure
                LOGGER.log(Level.FINEST,
                        "checking file structure on partition {0}",
                        deviceAndNumber);
                File liveDir = new File(mountPath, "live");
                if (liveDir.exists()) {
                    FilenameFilter squashFsFilter = new FilenameFilter() {
                        @Override
                        public boolean accept(File dir, String name) {
                            return name.endsWith(".squashfs");
                        }
                    };
                    String[] squashFileSystems = liveDir.list(squashFsFilter);
                    if (squashFileSystems.length > 0) {
                        isSystemPartition = true;
                        isBootPartition = false;
                    }
                }

                // cleanup
                if (!mountInfo.alreadyMounted()) {
                    umount();
                }
            } catch (DBusExecutionException | IOException ex) {
                throw new DBusException(ex.getMessage());
            }
        }
        return isSystemPartition;
    }

    /**
     * returns <code>true</code>, if this partition is a Debian Live persistence
     * partition, <code>false</code> otherwise
     *
     * @return <code>true</code>, if this partition is a Debian Live persistence
     * partition, <code>false</code> otherwise
     */
    public boolean isPersistencePartition() {

        try {

            String tmpIdLabel = DbusTools.getStringProperty(
                    getObjectPath(), "org.freedesktop.UDisks2.Block",
                    "IdLabel");

            if (tmpIdLabel.equals(PERSISTENCE_LABEL)) {
                return true;
            }
            for (String legacyLabel : LEGACY_PERSISTENCE_LABELS) {
                if (tmpIdLabel.equals(legacyLabel)) {
                    return true;
                }
            }
            return false;

        } catch (IOException | DBusException ex) {
            LOGGER.log(Level.SEVERE, "", ex);
        }

        return false;
    }

    /**
     * returns <code>true</code> if this partition is the exchange partition,
     * <code>false</code> otherwise
     *
     * @return <code>true</code> if this partition is the exchange partition,
     * <code>false</code> otherwise
     */
    public boolean isExchangePartition() {
        // First check the partition number:
        // The exchange partition is either the first partition (legacy version
        // where the boot partition was not the first partition) or the second
        // partition (current version where the boot partition MUST be the first
        // partition).
        if ((number != 1) && (number != 2)) {
            return false;
        }

        // Check partition and file system types:
        // The exchange partition can either be of type 0x07 (when using exFAT
        // or NTFS) or of type 0x0c (when using FAT32).
        // Older Lernstick versions even used 0x0e for the exchange partition.
        if (type.equals("0x07")) {
            // exFAT or NTFS?
            return (idType.equals("exfat") || idType.equals("ntfs"));
        } else if (type.equals("0x0c") || type.equals("0x0e")) {
            // FAT32?
            return (idType.equals("vfat"));
        }

        return false;
    }

    /**
     * returns <code>true</code>, if this partition is an active persistence
     * partition, <code>false</code> otherwise
     *
     * @return <code>true</code>, if this partition is an active persistence
     * partition, <code>false</code> otherwise
     * @throws DBusException if a D-BUS exception occurs
     * @throws java.io.IOException if an I/O exception occurs
     */
    public boolean isActivePersistencePartition()
            throws DBusException, IOException {

        if (isPersistencePartition()) {
            List<String> mountPaths = getMountPaths();
            for (String mountPath : mountPaths) {
                if (mountPath.startsWith(
                        "/lib/live/mount/persistence/")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * returns <code>true</code>, if the partition is mounted,
     * <code>false</code> otherwise
     *
     * @return <code>true</code>, if the partition is mounted,
     * <code>false</code> otherwise
     * @throws DBusException if an D-BUS exception occurs
     * @throws java.io.IOException if an I/O exception occurs
     */
    public boolean isMounted() throws DBusException, IOException {
        List<String> mountPaths = getMountPaths();
        return (mountPaths != null) && (!mountPaths.isEmpty());
    }

    /**
     * removes this partition from the storage device
     *
     * @throws IOException if an I/O exception occurs
     * @throws DBusException if a D-Bus exception occurs
     */
    public void remove() throws IOException, DBusException {

        // early return
        if (!umount()) {
            return;
        }

        ProcessExecutor processExecutor = new ProcessExecutor();
        int returnValue = processExecutor.executeProcess(true, true, "parted",
                "/dev/" + getStorageDevice().getDevice(),
                "rm", String.valueOf(getNumber()));
        if (returnValue != 0) {
            throw new IOException(
                    "removing partition " + this + " failed");
        }
    }

    public boolean luksFormat(String password) throws IOException {
        String script = "#!/bin/sh\n"
                + "echo \"" + password + "\" | "
                + CRYPTSETUP + "luksFormat /dev/" + deviceAndNumber + "\n";

        ProcessExecutor processExecutor = new ProcessExecutor(false);
        int returnValue = processExecutor.executeScript(script);

        return returnValue == 0;
    }

    public String luksOpen(String password) throws IOException {

        // TODO: any better idea for the mapping ID?
        String mappingID = deviceAndNumber;

        String script = "#!/bin/sh\n"
                + "echo \"" + password + "\" | "
                + "cryptsetup open --type luks /dev/" + deviceAndNumber
                + " " + mappingID;

        ProcessExecutor processExecutor = new ProcessExecutor(false);
        int returnValue = processExecutor.executeScript(script);

        if (returnValue != 0) {
            throw new IOException(
                    "Could not open LUKS partition /dev/" + deviceAndNumber);
        }

        return "/dev/mapper/" + mappingID;
    }

    public boolean isLuksEncrypted() {
        return idType.equals("crypto_LUKS");
    }

    public boolean isSecondaryPasswordSet() {
        return isLuksSlotUsed(1);
    }

    public boolean isLuksSlotUsed(int slot) {
        try {
            ProcessExecutor processExecutor = new ProcessExecutor(true);
            String script = "cryptsetup luksDump /dev/" + deviceAndNumber
                    + " | grep \"" + slot + ": luks2\"";
            int returnValue = processExecutor.executeScript(true, true, script);
            return returnValue == 0;
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "", ex);
        }
        return false;
    }

    public boolean addSecondaryLuksPassword(
            String existingPassword, String newPassword) throws IOException {
        return addLuksPassword(existingPassword, 1, newPassword);
    }

    public boolean addLuksPassword(
            String existingPassword, int slot, String newPassword)
            throws IOException {

        String script = "#!/bin/sh\n"
                + "set -e\n"
                + "KEYFILE=$(mktemp)\n"
                + "trap \"rm $KEYFILE\" EXIT\n"
                + "echo -n \"" + newPassword + "\"" + " > $KEYFILE\n"
                + "echo \"" + existingPassword + "\" | " + CRYPTSETUP
                + "luksAddKey --key-slot 1 /dev/" + deviceAndNumber
                + " $KEYFILE\n";

        ProcessExecutor processExecutor = new ProcessExecutor(false);
        int returnValue = processExecutor.executeScript(script);

        return returnValue == 0;
    }

    public boolean changeLuksPassword(int slot,
            String oldPassword, String newPassword) throws IOException {

        String script = "#!/bin/sh\n"
                + "set -e\n"
                + "KEYFILE=$(mktemp)\n"
                + "trap \"rm $KEYFILE\" EXIT\n"
                + "echo -n \"" + newPassword + "\"" + " > $KEYFILE\n"
                + "echo \"" + oldPassword + "\" | " + CRYPTSETUP
                + "luksChangeKey --key-slot " + slot
                + " /dev/" + deviceAndNumber + " $KEYFILE";

        ProcessExecutor processExecutor = new ProcessExecutor(false);
        int returnValue = processExecutor.executeScript(script);

        return returnValue == 0;
    }

    public boolean killSecondaryLuksSlot() throws IOException {
        return killLuksSlot(1);
    }

    public boolean killLuksSlot(int slot) throws IOException {

        // https://gitlab.com/cryptsetup/cryptsetup/-/issues/329
        String script = "#!/bin/bash\n"
                + "faketty () {\n"
                + "    script -qfc \"$(printf \"%q \" \"$@\")\"\n"
                + "}\n"
                + "faketty cryptsetup luksKillSlot --batch-mode /dev/"
                + deviceAndNumber + " " + slot;

        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.executeScript(true, true, script);

        // Beause of the strange contruct needed above we don't get a real
        // return value. Therefore we have to check again if the slot is used.
        return !isLuksSlotUsed(slot);
    }

    private Partition(String device, String number) throws DBusException {

        LOGGER.log(Level.FINE, "\n"
                + "    thread: {0}\n"
                + "    device: \"{1}\"\n"
                + "    number = {2}",
                new Object[]{Thread.currentThread().getName(), device, number});

        this.device = device;
        this.number = Integer.parseInt(
                number.substring(number.startsWith("p") ? 1 : 0));
        deviceAndNumber = device + number;

        if (DbusTools.DBUS_VERSION == DbusTools.DbusVersion.V1) {
            offset = DbusTools.getLongProperty(deviceAndNumber, "PartitionOffset");
            size = DbusTools.getLongProperty(deviceAndNumber, "PartitionSize");
            idLabel = DbusTools.getStringProperty(deviceAndNumber, "IdLabel");
            idType = DbusTools.getStringProperty(deviceAndNumber, "IdType");
            type = DbusTools.getStringProperty(deviceAndNumber, "PartitionType");
            isDrive = DbusTools.getBooleanProperty(deviceAndNumber, "DeviceIsDrive");

        } else {
            String objectPath = "/org/freedesktop/UDisks2/block_devices/"
                    + deviceAndNumber;
            String interfacePrefix = "org.freedesktop.UDisks2.";
            String partitionInterface = interfacePrefix + "Partition";
            offset = DbusTools.getLongProperty(
                    objectPath, partitionInterface, "Offset");
            size = DbusTools.getLongProperty(
                    objectPath, partitionInterface, "Size");
            type = DbusTools.getStringProperty(
                    objectPath, partitionInterface, "Type");

            String blockInterface = interfacePrefix + "Block";
            idLabel = DbusTools.getStringProperty(
                    objectPath, blockInterface, "IdLabel");
            idType = DbusTools.getStringProperty(
                    objectPath, blockInterface, "IdType");

            boolean tmpDrive = false;
            try {
                List<String> interfaceNames
                        = DbusTools.getInterfaceNames(objectPath);
                tmpDrive = !interfaceNames.contains(
                        interfacePrefix + "Partition");
            } catch (IOException | SAXException
                    | ParserConfigurationException ex) {
                LOGGER.log(Level.SEVERE, "", ex);
            }
            isDrive = tmpDrive;
        }
    }

    private String getObjectPath() throws IOException {
        if (isLuksEncrypted()) {
            return getClearTextObjectPath();
        } else {
            return "/org/freedesktop/UDisks2/block_devices/" + deviceAndNumber;
        }
    }

    private String getClearTextObjectPath() throws IOException {
        ProcessExecutor processExecutor = new ProcessExecutor(true);
        processExecutor.executeScript(true, true, "#!/bin/sh\n"
                + "dbus-send "
                + "--system "
                + "--dest=org.freedesktop.UDisks2 "
                + "--print-reply "
                + "/org/freedesktop/UDisks2/block_devices/" + deviceAndNumber
                + " org.freedesktop.DBus.Properties.Get "
                + "string:org.freedesktop.UDisks2.Encrypted "
                + "string:CleartextDevice "
                + "| grep block_devices "
                + "| awk '{ print $4}' "
                + "| tr -d '\"'");
        String clearTextObjectPath
                = processExecutor.getStdOut().stripTrailing();
        LOGGER.log(Level.INFO,
                "clearTextObjectPath: \"{0}\"", clearTextObjectPath);
        return clearTextObjectPath;
    }

    private String getLUKSMappingName() throws IOException {
        String script = "#!/bin/sh\n"
                + "lsblk -nl -o NAME,TYPE /dev/" + deviceAndNumber
                + " | " + "grep crypt$ | awk '{ print $1 }'";
        ProcessExecutor executor = new ProcessExecutor(true);
        executor.executeScript(true, true, script);
        return executor.getStdOut().stripTrailing();
    }

    private String getBlockDevice() throws IOException {
        if (isLuksEncrypted()) {
            return "mapper/" + getLUKSMappingName();
        } else {
            return deviceAndNumber;
        }
    }

    private void handleUmountException(Exception ex) {
        LOGGER.log(Level.WARNING, "", ex);
        try {
            ProcessExecutor processExecutor = new ProcessExecutor();
            int returnValue = processExecutor.executeProcess(true, true,
                    "fuser", "-v", "-m", "/dev/" + deviceAndNumber);
            if (returnValue == 0) {
                //while (returnValue == 0) {
                LOGGER.log(Level.INFO, "/dev/{0} is still being used by the "
                        + "following processes:\n{1}",
                        new Object[]{
                            deviceAndNumber,
                            processExecutor.getStdOut()
                        });
                Thread.sleep(1000);
//                returnValue = processExecutor.executeProcess(true, true,
//                        "fuser", "-v", "-m", "/dev/" + deviceAndNumber);
            }
        } catch (InterruptedException ex2) {
            LOGGER.log(Level.SEVERE, "", ex2);
        }
    }
}
