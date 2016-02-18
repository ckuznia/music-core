package music.core.binarytree;

import java.io.Serializable;

import music.core.Track;

public class Node implements Serializable {
	
	private static final long serialVersionUID = 1L;
	
	private Track track;
	private Node parent;
	private Node leftChild;
	private Node rightChild;
	
	public Node(Track track) {
		this.setTrack(track);
	}

	public Track getTrack() {
		return track;
	}

	public void setTrack(Track track) {
		this.track = track;
	}

	public Node getParent() {
		return parent;
	}

	public void setParent(Node parent) {
		this.parent = parent;
	}

	public Node getLeftChild() {
		return leftChild;
	}

	public void setLeftChild(Node leftChild) {
		this.leftChild = leftChild;
	}

	public Node getRightChild() {
		return rightChild;
	}

	public void setRightChild(Node rightChild) {
		this.rightChild = rightChild;
	}
}
