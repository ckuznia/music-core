package music.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jaudiotagger.tag.FieldKey;

import music.core.binarytree.BinaryTree;
import music.core.binarytree.Node;

public class Storage {
	
	private static final Logger log = LogManager.getLogger(Storage.class);
	
	private final File database; // The actual database
	private final File download; // A staging folder before files are placed into the database
	private final BinaryTree tree;
	
	/**
	 * Sets up a Storage utility class that both server and client can use. Just specify the main folder 
	 * the program will use for storage (storage), what folder the database will be stored (databaseName),
	 * and what folder the downloads will be stored (downloadName). Both databaseFolder and downloadFolder should
	 * be folders located within storagePath.
	 * 
	 * @param storage The path to the programs main folder.
	 * @param databaseName The actual database folder where music files are stored
	 * @param downloadName The staging folder where downloaded files are placed
	 */
	public Storage(String storageName, String databaseName, String downloadName) {
		File storage = new File(storageName);
		this.database = new File(storage.getAbsolutePath() + databaseName);
		this.download = new File(storage.getAbsolutePath() + downloadName);
		
		tree = new BinaryTree();
		
		// Checking if main application folder exists, or can be created.
		if(!(storage.exists() || storage.mkdirs())) {
			// The main application folder was not found and could not be created.
			log.error("The main application folder " + storage.getAbsolutePath() +" was not found and could not be created. Exiting application");
			System.exit(2);
		}
		log.debug("Application folder " + storage.getAbsolutePath() +" found.");
		
		// Creating required sub-folders for file storage...
		try {
			// Creating database and download folder
			if(!(database.exists() || database.mkdirs())) log.error("Error creating " + database.getAbsolutePath());
			if(!(download.exists() || download.mkdirs())) log.error("Error creating " + download.getAbsolutePath());
			
		} catch(SecurityException e) {
			log.error("There were problems creating the required application folders. Possibly a permissions issue. Try running with sudo or using root user.", e);
			System.exit(4);
		}
		log.debug("Application sub-folders " + database.getAbsolutePath() + " and " + download.getAbsolutePath() + " found.");
		
		// Load the database and search tree
		update();
		
		log.debug("Application storage setup complete.");
	}
	
	/**
	 * Updates both the database and search tree. This is equivalent to running <code>updateDatabase()</code> and <code>updateTree()</code>, respectively.
	 */
	public synchronized void update() {
		updateDatabase();
		updateTree();
	}
	
	/**
	 * Takes all files within the downloads folder and runs each one through a test. If the test is passed, then the file will be added to
	 * the database. Otherwise the file will be deleted. This updates the database and cleans out the downloads folder.
	 * 
	 * <p> NOTE: The file is only moved into the database folder. It will NOT be added to the binary search tree.
	 */
	public synchronized void updateDatabase() {
		log.debug("Updating database...");
		
		ArrayList<String> songPaths = new ArrayList<String>();
		// Populating list of song paths
		retrieveFiles(getDownloadPath(), songPaths, false);
		
		// Iterate through each file in the downloads folder and place them
		// in their corresponding artist folder.
		for(String songPath: songPaths) {
			File song = new File(songPath);
			if(song.exists()) {
				String fileName = song.getName();
				// Parse file name to find out extension type
				int dotIndex = fileName.lastIndexOf('.');
				String extension = "";
				if(dotIndex != -1) extension = fileName.substring(dotIndex, fileName.length());
				extension = extension.toLowerCase();
				
				// Check file to make sure it is legitimate
				if(!checkFile(song, extension)) continue;
				
				// Retrieve artist, album, and name of downloaded song
				String artist = Track.getField(FieldKey.ARTIST, song.getAbsolutePath());
				String album = Track.getField(FieldKey.ALBUM, song.getAbsolutePath());
				String title = Track.getField(FieldKey.TITLE, song.getAbsolutePath());
				
				// Replacing all invalid characters with underscores (for folder naming)
				// and converting to all lower case
				String valid = "[^a-zA-Z0-9.-]"; // List of characters to not replace (NOT marked by ^)
				artist = artist.replaceAll(valid, "_").toLowerCase();
				album = album.replaceAll(valid, "_").toLowerCase();
				title = title.replaceAll(valid, "_").toLowerCase();
				
				// The new location to move the file
				String dirLocation = database.getAbsolutePath() + "/" + artist + "/" + album;
				String fileLocation = dirLocation + "/" + title + extension;
				
				File newDirectory = new File(dirLocation);
				File newFile = new File(fileLocation);
				try {
					// Create directory for new file
					if(!newDirectory.exists()) newDirectory.mkdirs();
					
					
					// Move the file. If the file already exists, replace it.
					Path path = Files.move(song.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
					log.debug("Created " + path);
				} catch (IOException e) {
					log.error("Error moving file " + songPath + " from downloads folder.", e);
				}
				
				// NOTE: The file has been moved into the database, but NOT into the binary search tree.
				
			} else {
				log.error("File " + song.getAbsolutePath() + " could not be found.");
			}
		}
		log.debug("Done.");
		
		// All files have either been moved into the database or deleted. However there may be folders
		// left over that we will delete.
		
		// First check to make sure there are no files left, just directories
		ArrayList<String> files = new ArrayList<String>();
		// Populating files list
		retrieveFiles(getDownloadPath(), files, false);
		if(files.size() > 0) {
			log.error("Files still remained after cleaning downloads folder: ");
			for(String file: files) {
				log.error(file + " remained.");
			}
			log.error("Cleaning of downloads folder will be CANCELLED.");
			return;
		}
		
		log.debug("Removing empty folders in " + getDownloadPath());
		// If there are only directories left, remove them
		File downloadFolder = new File(getDownloadPath());
		String[] items = downloadFolder.list();
		
		for(String item: items) {
			// Delete each folder regardless of its contents
			deleteFolder(getDownloadPath() + "/" + item);
		}
		
		log.debug("Done.");
	}
	
	/**
	 * Recursively deletes a folder and any files or folders that it may contain.
	 * 
	 * @param directory
	 */
	private void deleteFolder(String directory) {
		File item = new File(directory);
		
		// Check if path is a file, if so delete it
		if(!item.isDirectory()) item.delete();
		else {
			// Remove any sub-folder and files
			String[] subItems = item.list();
			for(String itemName: subItems) {
				deleteFolder(item.getAbsolutePath() + "/" + itemName);
			}
			
			// Then delete the folder
			item.delete();
		}
	}
	
	/**
	 * Checks to make sure the specified file is not a directory, is a supported audio file format, and that
	 * there are no issues reading track data from the file. Returns false otherwise.
	 * 
	 * <p>If false is returned, this means that the file did not pass the test and has therefore been deleted.
	 * 
	 * @param file the file to be checked
	 * @param extension the extension for the specified file
	 * @returns true if and only if the file is NOT a directory, is supported, and its track data can be read properly. False otherwise.
	 */
	private boolean checkFile(File file, String extension) {
		// Checking if the file is a directory
		if(file.isDirectory()) {
			log.error("File " + file.getName() + " is a directory, and therefore cannot be moved into the database. Deleting folder");
			if(file.delete()) log.error("Done.");
			else log.error("There was a problem deleting the file.");
			return false;
		}
		
		// Checking to make sure that the file extension is a supported audio file format
		if(	extension.equals(".mp3") || // Mp3
				extension.equals(".mp4")  || // Mp4
				extension.equals(".m4p")  ||
				extension.equals(".m4a")  ||
				extension.equals(".flac") || // FLAC
				extension.equals(".ogg") || // Ogg Vorbis
				extension.equals(".oga") ||
				extension.equals(".wma") || // Wma
				extension.equals(".wav") || // Wav
				extension.equals(".ra") || // Real
				extension.equals(".ram")) {
			// The file is a supported format, attempt to retrieve song data from it
			
			// Retrieve artist, album, and name of downloaded song
			String artist = Track.getField(FieldKey.ARTIST, file.getAbsolutePath());
			String album = Track.getField(FieldKey.ALBUM, file.getAbsolutePath());
			String title = Track.getField(FieldKey.TITLE, file.getAbsolutePath());
			
			// If anything could not be read, note that there were issues with the file and delete it
			if(artist == null || album == null || title == null) {
				log.debug("There was a problem getting track data from " + file.getName() + ". Deleting file...");
				if(file.delete()) log.error("Done.");
				else log.error("There was a problem deleting the file.");
				return false;
			}
			return true;
		}
		else {
			// Incorrect file format
			log.error("File " + file.getName() + " is not a supported audio file. Deleting file...");
			
			// Deleting file
			if(file.delete()) log.debug("Done.");
			else log.error("There was a problem deleting the file.");
			return false;
		}
	}
	
	/**
	 * Reads the track files contained in the database. Newly existing track files are then added to the binary search tree.
	 * This brings the tree up to date with the current database.
	 */
	public synchronized void updateTree() {
		log.debug("Updating binary search tree...");
		
		// Creating an array to store file paths for files currently in the database
		ArrayList<String> databasePaths = new ArrayList<String>();
		// Populating that array
		retrieveFiles(getDatabasePath(), databasePaths, false);
		
		// Creating an array to store file paths for files currently in the binary tree
		ArrayList<String> treePaths = new ArrayList<String>();
		// Populating that array
		for(Node node: tree.getNodes()) {
			treePaths.add(node.getTrack().getPath());
		}
		
		// Any paths that already exist in the binary tree are not needed, and therefore will be removed.
		// This leaves only the file paths that need to be added to the tree left.
		databasePaths.removeAll(treePaths);
		ArrayList<String> newPaths = databasePaths;
		
		// Check if there are still paths to add to the tree
		if(newPaths.size() > 0) {
			// Now use those paths to create new Track objects
			ArrayList<Track> tracks = new ArrayList<Track>();
			for(String path: newPaths) {
				Track track = new Track(path);
				tracks.add(track);
			}
			Track[] trackArray = tracks.toArray(new Track[tracks.size()]);
			// Sorting in alphabetical order
			Arrays.sort(trackArray);
			
			// Now adding those tracks to the tree
			tree.add(trackArray);
		}
		log.debug("Done.");
	}
	
	/**
	 * Returns a list of file paths for each file located under the specified folder, and any sub-folders
	 * that may be located in there. Pass in true for getDirectories if you wish for directories to be included
	 * in the returned list.
	 * 
	 * @param rootFolder the folder with which to search for files
	 * @param filePaths the array to populate with found file paths
	 * @param getDirectories true if directories should be included in the list
	 * @return list of file-paths for files under the specified root folder
	 */
	private void retrieveFiles(String rootFolder, ArrayList<String> filePaths, boolean getDirectories) {
		File folder = new File(rootFolder);
		String[] subItems = folder.list(); // Getting list of sub-directories, these could be files or folders
		
		// Look at each file/folder in the list. If the item is a file, add it to the file-path array.
		// If it is a folder, then only add it if getDirectories is true, and recursively retrieveFiles() from that folder.
		for(String itemName: subItems) {
			File item = new File(rootFolder + "/" + itemName);
			if(item.isDirectory()) {
				if(getDirectories) filePaths.add(item.getAbsolutePath());
				retrieveFiles(item.getAbsolutePath(), filePaths, getDirectories);
			}
			else filePaths.add(item.getAbsolutePath());
		}
	}
	
	public String getDownloadPath() { 
		return download.getAbsolutePath();
	}
	
	public String getDatabasePath() {
		return database.getAbsolutePath();
	}
	
	public BinaryTree getBinaryTree() {
		return tree;
	}
}
