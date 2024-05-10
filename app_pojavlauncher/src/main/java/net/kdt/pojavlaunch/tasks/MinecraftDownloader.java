package net.kdt.pojavlaunch.tasks;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;
import static net.kdt.pojavlaunch.Tools.BYTE_TO_MB;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.AtomicMonitor;
import net.kdt.pojavlaunch.JAssetInfo;
import net.kdt.pojavlaunch.JAssets;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.JRE17Util;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.ServerModpackConfig;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.mirrors.MirrorTamperedException;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.DependentLibrary;
import net.kdt.pojavlaunch.value.MinecraftClientInfo;
import net.kdt.pojavlaunch.value.MinecraftLibraryArtifact;
import net.kdt.pojavlaunch.value.ServerFileInfo;
import net.kdt.pojavlaunch.value.SmallFileComparator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class MinecraftDownloader {
    public static final String MINECRAFT_RES = "https://resources.download.minecraft.net/";
    private AtomicReference<Exception> mDownloaderThreadException;
    private ArrayList<DownloaderTask> mScheduledDownloadTasks;
    private AtomicLong mDownloadFileCounter;
    private AtomicLong mDownloadSizeCounter;
    private long mDownloadFileCount;
    private File mSourceJarFile; // The source client JAR picked during the inheritance process
    private File mTargetJarFile; // The destination client JAR to which the source will be copied to.

    private static final ThreadLocal<byte[]> sThreadLocalDownloadBuffer = new ThreadLocal<>();
    private static Thread downloaderThread;
    private static boolean mInterruptDownload = false;

    /**
     * Start the game version download process on the global executor service.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param version The JMinecraftVersionList.Version from the version list, if available
     * @param realVersion The version ID (necessary)
     * @param listener The download status listener
     */
    public void start(@Nullable Activity activity, @Nullable JMinecraftVersionList.Version version,
                      @NonNull String realVersion, // this was there for a reason
                      @NonNull AsyncMinecraftDownloader.DoneListener listener) {
        Logger.appendToLog("MinecraftDownloader: iniciando");
        downloaderThread = new Thread(() -> {
            try {
                Logger.appendToLog("MinecraftDownloader: iniciando donwload");
                downloadGame(activity, version, realVersion);
                if (!mInterruptDownload) {
                    listener.onDownloadDone();
                }
            } catch (Exception e) {
                Logger.appendToLog("MinecraftDownloader: erro de exeption no downloadGame()");
                if (!mInterruptDownload) {
                    listener.onDownloadFailed(e);
                }
            } finally {
                Logger.appendToLog("MinecraftDownloader: limpando progresso");
                mInterruptDownload = false;
                ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT);
            }
        });
        downloaderThread.start();
    }

    public static void interrupt() {
        mInterruptDownload = true;
        if(downloaderThread != null) {
            downloaderThread.interrupt();
        }
    }

    public static boolean isR() {
        if(downloaderThread == null) return false;
        return downloaderThread.isInterrupted();
    }

    /**
     * Download the game version.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @throws Exception when an exception occurs in the function body or in any of the downloading threads.
     */
    private void downloadGame(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws Exception {
        // Put up a dummy progress line, for the activity to start the service and do all the other necessary
        // work to keep the launcher alive. We will replace this line when we will start downloading stuff.
        Logger.appendToLog("MinecraftDownloader: iniciando variáveis para o processamento do download");
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0, R.string.newdl_starting);

        mTargetJarFile = createGameJarPath(versionName);
        mScheduledDownloadTasks = new ArrayList<>();
        mDownloadFileCounter = new AtomicLong(0);
        mDownloadSizeCounter = new AtomicLong(0);
        mDownloaderThreadException = new AtomicReference<>(null);

        if(!downloadAndProcessMetadata(activity, verInfo, versionName)) {
            throw new RuntimeException(activity.getString(R.string.exception_failed_to_unpack_jre17));
        }

        ArrayBlockingQueue<Runnable> taskQueue =
                new ArrayBlockingQueue<>(mScheduledDownloadTasks.size(), false);
        ThreadPoolExecutor downloaderPool =
                new ThreadPoolExecutor(4, 4, 500, TimeUnit.MILLISECONDS, taskQueue);

        // I have tried pre-filling the queue directly instead of doing this, but it didn't work.
        // What a shame.
        for(DownloaderTask scheduledTask : mScheduledDownloadTasks) downloaderPool.execute(scheduledTask);
        downloaderPool.shutdown();

        Logger.appendToLog("MinecraftDownloader: iniciando download de arquivos do jogo");
        try {
            while (mDownloaderThreadException.get() == null &&
                    !downloaderPool.awaitTermination(33, TimeUnit.MILLISECONDS) && !mInterruptDownload) {
                long dlFileCounter = mDownloadFileCounter.get();
                int progress = (int)((dlFileCounter * 100L) / mDownloadFileCount);
                ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, progress,
                        R.string.newdl_downloading_game_files, dlFileCounter,
                        mDownloadFileCount, (double)mDownloadSizeCounter.get() / (1024d * 1024d));
            }
            if(mInterruptDownload) {
                Logger.appendToLog("MinecraftDownloader: download do jogo cancelado");
                throw new InterruptedException("Download was cancelled.");
            }
            Exception thrownException = mDownloaderThreadException.get();
            if(thrownException != null) {
                Logger.appendToLog("MinecraftDownloader: erro de download");
                throw thrownException;
            } else {
                ensureJarFileCopy();
            }
        } catch (InterruptedException e) {
            Logger.appendToLog("MinecraftDownloader: download interrompido");
            // Interrupted while waiting, which means that the download was cancelled.
            // Kill all downloading threads immediately, and ignore any exceptions thrown by them
            downloaderPool.shutdownNow();
        } finally {
            Logger.appendToLog("MinecraftDownloader: limpeza do progresso");
            // Limpeza adequada
            mInterruptDownload = false;
            ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT);
        }
    }

    private File createGameJsonPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".json");
    }

    private File createGameJarPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".jar");
    }

    /**
     * Ensure that there is a copy of the client JAR file in the version folder, if a copy is
     * needed.
     * @throws IOException if the copy fails
     */
    private void ensureJarFileCopy() throws IOException {
        if(mSourceJarFile == null) return;
        if(mSourceJarFile.equals(mTargetJarFile)) return;
        if(mTargetJarFile.exists()) return;
        FileUtils.ensureParentDirectory(mTargetJarFile);
        Log.i("NewMCDownloader", "Copying " + mSourceJarFile.getName() + " to "+mTargetJarFile.getAbsolutePath());
        org.apache.commons.io.FileUtils.copyFile(mSourceJarFile, mTargetJarFile, false);
    }

    private File downloadGameJson(JMinecraftVersionList.Version verInfo) throws IOException, MirrorTamperedException {
        File targetFile = createGameJsonPath(verInfo.id);
        if(verInfo.sha1 == null && targetFile.canRead() && targetFile.isFile())
            return targetFile;
        FileUtils.ensureParentDirectory(targetFile);
        try {
            DownloadUtils.ensureSha1(targetFile, LauncherPreferences.PREF_VERIFY_MANIFEST ? verInfo.sha1 : null, () -> {
                ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                        R.string.newdl_downloading_metadata, targetFile.getName());
                DownloadMirror.downloadFileMirrored(DownloadMirror.DOWNLOAD_CLASS_METADATA, verInfo.url, targetFile);
                return null;
            });
        }catch (DownloadUtils.SHA1VerificationException e) {
            if(DownloadMirror.isMirrored()) throw new MirrorTamperedException();
            else throw e;
        }
        return targetFile;
    }

    private JAssets downloadAssetsIndex(JMinecraftVersionList.Version verInfo) throws IOException{
        JMinecraftVersionList.AssetIndex assetIndex = verInfo.assetIndex;
        if(assetIndex == null || verInfo.assets == null) return null;
        File targetFile = new File(Tools.ASSETS_PATH, "indexes"+ File.separator + verInfo.assets + ".json");
        FileUtils.ensureParentDirectory(targetFile);
        DownloadUtils.ensureSha1(targetFile, assetIndex.sha1, ()-> {
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                    R.string.newdl_downloading_metadata, targetFile.getName());
            DownloadMirror.downloadFileMirrored(DownloadMirror.DOWNLOAD_CLASS_METADATA, assetIndex.url, targetFile);
            return null;
        });
        return Tools.GLOBAL_GSON.fromJson(Tools.read(targetFile), JAssets.class);
    }

    private MinecraftClientInfo getClientInfo(JMinecraftVersionList.Version verInfo) {
        Map<String, MinecraftClientInfo> downloads = verInfo.downloads;
        if(downloads == null) return null;
        return downloads.get("client");
    }

    /**
     * Download (if necessary) and process a version's metadata, scheduling all downloads that this
     * version needs.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @return false if JRE17 installation failed, true otherwise
     * @throws IOException if the download of any of the metadata files fails
     */
    private boolean downloadAndProcessMetadata(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws IOException, MirrorTamperedException, DownloaderException {
        Logger.appendToLog("MinecraftDownloader: iniciando download do modpack");
        File versionJsonFile = createGameJsonPath(versionName);
        //if(verInfo != null) versionJsonFile = downloadGameJson(verInfo);
        //else versionJsonFile = createGameJsonPath(versionName);
        if(versionJsonFile.canRead())  {
            verInfo = Tools.GLOBAL_GSON.fromJson(Tools.read(versionJsonFile), JMinecraftVersionList.Version.class);
        } else {
            throw new IOException("Unable to read Version JSON for version " + versionName);
        }

        verInfo = Tools.getVersionInfo(versionName);
        final ServerModpackConfig config = ServerModpackConfig.load(versionName);
        if(activity != null && !JRE17Util.installNewJreIfNeeded(activity, verInfo, config)){
            return false;
        }

        try {
            Logger.appendToLog("MinecraftDownloader: baixando modpack");
            Log.i("Modpack","Downloading modpack files...");
            downloadModpackFiles(verInfo, new File(config.getGameDirectory()));
        } catch (DownloaderException e) {
            Logger.appendToLog("MinecraftDownloader: erro ao baixar modpack");
            ProgressKeeper.submitProgress(ProgressLayout.DOWNLOAD_MINECRAFT, -1, -1);
            throw e;
        } catch (Exception e) {
            Logger.appendToLog("MinecraftDownloader: erro ao baixar modpack");
            e.printStackTrace();
            ProgressKeeper.submitProgress(ProgressLayout.DOWNLOAD_MINECRAFT, -1, -1);
            if(!Tools.DOWNLOADED.exists()) throw new DownloaderException(e);
        }

        JAssets assets = downloadAssetsIndex(verInfo);
        if(assets != null) scheduleAssetDownloads(assets);


        MinecraftClientInfo minecraftClientInfo = getClientInfo(verInfo);
        if(minecraftClientInfo != null) scheduleGameJarDownload(minecraftClientInfo, versionName);

        if(verInfo.libraries != null) scheduleLibraryDownloads(verInfo.libraries);

        if(verInfo.logging != null) scheduleLoggingAssetDownloadIfNeeded(verInfo.logging);

        if(Tools.isValidString(verInfo.inheritsFrom)) {
            JMinecraftVersionList.Version inheritedVersion = AsyncMinecraftDownloader.getListedVersion(verInfo.inheritsFrom);
            // Infinite inheritance !?! :noway:
            return downloadAndProcessMetadata(activity, inheritedVersion, verInfo.inheritsFrom);
        }
        return true;
    }

    public void downloadModpackFiles(JMinecraftVersionList.Version version, File destination) throws IOException, DownloaderException{
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(5, 5, 500, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        final AtomicBoolean interrupt = new AtomicBoolean(true);
        final AtomicLong downloadProgress = new AtomicLong(0);
        final SmallFileComparator comparator = new SmallFileComparator();
        long downloadSize = 0;
        if(version.custom_files != null) {
            Arrays.sort(version.custom_files, comparator); //Breeze through the small files first, deal with the large ones later
            for(ServerFileInfo serverFileInfo : version.custom_files) {
                downloadSize += serverFileInfo.size;
                serverFileInfo.setDownloaderData(destination, new AtomicMonitor(downloadProgress), interrupt);
                executor.execute(serverFileInfo);
            }
        }
        if(version.custom_mods != null) {
            ArrayList<String> modsThatExist = new ArrayList<>(version.custom_mods.size());
            Arrays.sort(version.custom_mods.values().toArray(new ServerFileInfo[0]), comparator);
            for(ServerFileInfo serverFileInfo : version.custom_mods.values()) {
                {
                    String path = serverFileInfo.path;
                    int slashIndex = path.lastIndexOf('/');
                    if(slashIndex != -1) modsThatExist.add(path.substring(slashIndex+1));
                    else modsThatExist.add(path);
                }
                downloadSize += serverFileInfo.size;
                serverFileInfo.setDownloaderData(destination, new AtomicMonitor(downloadProgress), interrupt);
                executor.execute(serverFileInfo);
            }
            File modsFolder = new File(destination, "modstore");
            if(modsFolder.isDirectory()) {
                File[] modFiles =  modsFolder.listFiles();
                if(modFiles != null) for(File mod : modFiles) {
                    if(mod.isFile() && !modsThatExist.contains(mod.getName())) {
                        Log.i("ExtraModRemoval", "Found extra mod file: "+mod.getName());
                        if(!mod.delete()) {
                            throw new IOException("Failed to delete mod "+mod.getName());
                        }
                    }
                }
            }
        }
        executor.shutdown();
        try {
            while (!executor.awaitTermination(150, TimeUnit.MILLISECONDS) && interrupt.get()) {
                long d = downloadProgress.get();
                ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, (int)(((double)d / downloadSize)*100), R.string.mcl_launch_downloading_progress, "mods", d/BYTE_TO_MB, downloadSize/BYTE_TO_MB);
            }
            if(!interrupt.get()) throw new IOException("Failed to download a mod file");
            executor.shutdownNow();
        }catch (InterruptedException ignored) {
            executor.shutdownNow();
            throw new DownloaderException();
        }
    }

    private void growDownloadList(int addedElementCount) {
        mScheduledDownloadTasks.ensureCapacity(mScheduledDownloadTasks.size() + addedElementCount);
    }

    private void scheduleDownload(File targetFile, int downloadClass, String url, String sha1,
                                  long size, boolean skipIfFailed) throws IOException {
        FileUtils.ensureParentDirectory(targetFile);
        mDownloadFileCount++;
        mScheduledDownloadTasks.add(
                new DownloaderTask(targetFile, downloadClass, url, sha1, size, skipIfFailed)
        );
    }

    private void scheduleLibraryDownloads(DependentLibrary[] dependentLibraries) throws IOException {
        Tools.preProcessLibraries(dependentLibraries);
        growDownloadList(dependentLibraries.length);
        for(DependentLibrary dependentLibrary : dependentLibraries) {
            // Don't download lwjgl, we have our own bundled in.
            if(dependentLibrary.name.startsWith("org.lwjgl")) continue;

            String libArtifactPath = Tools.artifactToPath(dependentLibrary);
            String sha1 = null, url = null;
            long size = 0;
            boolean skipIfFailed = false;
            if(dependentLibrary.downloads != null) {
                if(dependentLibrary.downloads.artifact != null) {
                    MinecraftLibraryArtifact artifact = dependentLibrary.downloads.artifact;
                    sha1 = artifact.sha1;
                    url = artifact.url;
                    size = artifact.size;
                } else {
                    // If the library has a downloads section but doesn't have an artifact in
                    // it, it is likely natives-only, which means it can be skipped.
                    Log.i("NewMCDownloader", "Skipped library " + dependentLibrary.name + " due to lack of artifact");
                    continue;
                }
            }
            if(url == null) {
                url = (dependentLibrary.url == null
                        ? "https://libraries.minecraft.net/"
                        : dependentLibrary.url.replace("http://","https://")) + libArtifactPath;
                skipIfFailed = true;
            }
            if(!LauncherPreferences.PREF_CHECK_LIBRARY_SHA) sha1 = null;
            scheduleDownload(new File(Tools.DIR_HOME_LIBRARY, libArtifactPath),
                    DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                    url, sha1, size, skipIfFailed
            );
        }
    }

    private void scheduleAssetDownloads(JAssets assets) throws IOException {
        Map<String, JAssetInfo> assetObjects = assets.objects;
        if(assetObjects == null) return;
        Set<String> assetNames = assetObjects.keySet();
        growDownloadList(assetNames.size());
        for(String asset : assetNames) {
            JAssetInfo assetInfo = assetObjects.get(asset);
            if(assetInfo == null) continue;
            File targetFile;
            String hashedPath = assetInfo.hash.substring(0, 2) + File.separator + assetInfo.hash;
            String basePath = assets.mapToResources ? Tools.OBSOLETE_RESOURCES_PATH : Tools.ASSETS_PATH;
            if(assets.virtual || assets.mapToResources) {
                targetFile = new File(basePath, asset);
            } else {
                targetFile = new File(basePath, "objects" + File.separator + hashedPath);
            }
            String sha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ? assetInfo.hash : null;
            scheduleDownload(targetFile,
                    DownloadMirror.DOWNLOAD_CLASS_ASSETS,
                    MINECRAFT_RES + hashedPath,
                    sha1,
                    assetInfo.size,
                    false);
        }
    }

    private void scheduleLoggingAssetDownloadIfNeeded(JMinecraftVersionList.LoggingConfig loggingConfig) throws IOException {
        if(loggingConfig.client == null || loggingConfig.client.file == null) return;
        JMinecraftVersionList.FileProperties loggingFileProperties = loggingConfig.client.file;
        File internalLoggingConfig = new File(Tools.DIR_DATA + File.separator + "security",
                loggingFileProperties.id.replace("client", "log4j-rce-patch"));
        if(internalLoggingConfig.exists()) return;
        File destination = new File(Tools.DIR_GAME_NEW, loggingFileProperties.id);
        scheduleDownload(destination,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                loggingFileProperties.url,
                loggingFileProperties.sha1,
                loggingFileProperties.size,
                false);
    }

    private void scheduleGameJarDownload(MinecraftClientInfo minecraftClientInfo, String versionName) throws IOException {
        File clientJar = createGameJarPath(versionName);
        String clientSha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ?
                minecraftClientInfo.sha1 : null;
        growDownloadList(1);
        scheduleDownload(clientJar,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                minecraftClientInfo.url,
                clientSha1,
                minecraftClientInfo.size,
                false
        );
        // Store the path of the JAR to copy it into our new version folder later.
        mSourceJarFile = clientJar;
    }

    private static byte[] getLocalBuffer() {
        byte[] tlb = sThreadLocalDownloadBuffer.get();
        if(tlb != null) return tlb;
        tlb = new byte[32768];
        sThreadLocalDownloadBuffer.set(tlb);
        return tlb;
    }

    private final class DownloaderTask implements Runnable, Tools.DownloaderFeedback {
        private final File mTargetPath;
        private final String mTargetUrl;
        private String mTargetSha1;
        private final int mDownloadClass;
        private final boolean mSkipIfFailed;
        private int mLastCurr;
        private final long mDownloadSize;

        DownloaderTask(File targetPath, int downloadClass, String targetUrl, String targetSha1,
                       long downloadSize, boolean skipIfFailed) {
            this.mTargetPath = targetPath;
            this.mTargetUrl = targetUrl;
            this.mTargetSha1 = targetSha1;
            this.mDownloadClass = downloadClass;
            this.mDownloadSize = downloadSize;
            this.mSkipIfFailed = skipIfFailed;
        }

        @Override
        public void run() {
            try {
                runCatching();
            }catch (Exception e) {
                mDownloaderThreadException.set(e);
            }
        }

        private void runCatching() throws Exception {
            if(Tools.isValidString(mTargetSha1)) {
                verifyFileSha1();
            }else {
                mTargetSha1 = null; // Nullify SHA1 as DownloadUtils.ensureSha1 only checks for null,
                // not for string validity
                if(mTargetPath.exists()) finishWithoutDownloading();
                else downloadFile();
            }
        }

        private void verifyFileSha1() throws Exception {
            if(mTargetPath.isFile() && mTargetPath.canRead() && Tools.compareSHA1(mTargetPath, mTargetSha1)) {
                finishWithoutDownloading();
            } else {
                // Rely on the download function to throw an IOE in case if the file is not
                // writable/not a file/etc...
                downloadFile();
            }
        }

        private void downloadFile() throws Exception {
            try {
                DownloadUtils.ensureSha1(mTargetPath, mTargetSha1, () -> {
                    DownloadMirror.downloadFileMirrored(mDownloadClass, mTargetUrl, mTargetPath,
                            getLocalBuffer(), this);
                    return null;
                });
            }catch (Exception e) {
                if(!mSkipIfFailed) throw e;
            }
            mDownloadFileCounter.incrementAndGet();
        }

        private void finishWithoutDownloading() {
            mDownloadFileCounter.incrementAndGet();
            mDownloadSizeCounter.addAndGet(mDownloadSize);

            if (mDownloadFileCounter.get() == mDownloadFileCount) {
                generateFileAfterDownload();
            }
        }

        private void generateFileAfterDownload() {
            try {
                Tools.DOWNLOADED.createNewFile();
            } catch (IOException e){}
        }

        @Override
        public void updateProgress(int curr, int max) {
            mDownloadSizeCounter.addAndGet(curr - mLastCurr);
            mLastCurr = curr;
        }
    }

    private static class DownloaderException extends Exception {
        public DownloaderException() {}
        public DownloaderException(Throwable e) {
            super(e);
        }
    }
}