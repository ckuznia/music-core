package music.core;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;

public class Track implements Comparable<Track>, Serializable {
	
	private static final long serialVersionUID = 1L;

	private static final Logger log = LogManager.getLogger(Track.class);
	
	private final String title;
	private final String album;
	private final String artist;
	
	// The artist, album, and track name are all used to identify a song.
	// Concatenating these values into one String gives the song an "ID value".
	// By checking the ID against other songs, the Song object can be properly
	// stored into the binary tree.
	private final String ID;
	
	private transient final String PATH;
	
	public Track(final String filePath) {
		this.PATH = filePath;
		title = getField(FieldKey.TITLE, filePath);
		album = getField(FieldKey.ALBUM, filePath);
		artist = getField(FieldKey.ARTIST, filePath);
		
		// Since Song objects in the binary tree are stored alphabetically by ID,
		// changing the order of the ID concatenation will change the order of the tree.
		String multiCaseID = artist + album + title;
		ID = multiCaseID.toLowerCase();
	}
	
	public static String getField(FieldKey key, String filePath) {
		try {
			AudioFile song = AudioFileIO.read(new File(filePath));
			Tag tag = song.getTag();
			return tag.getFirst(key);
		} catch (CannotReadException | IOException | TagException | ReadOnlyFileException | InvalidAudioFrameException e) {
			log.error("Error getting field " + key + " for " + filePath, e);
		}
		return null;
	}

	public String getTitle() {
		return title;
	}

	public String getAlbum() {
		return album;
	}

	public String getArtist() {
		return artist;
	}

	public String getID() {
		return ID;
	}
	
	public String getPath() {
		return PATH;
	}

	@Override
	public int compareTo(Track track) {
		String ID1 = getID();
		String ID2 = track.getID();
		return ID1.compareTo(ID2);
	}
}
