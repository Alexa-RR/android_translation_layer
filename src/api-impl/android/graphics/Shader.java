package android.graphics;

public class Shader {

	public enum TileMode {
		CLAMP,
		MIRROR,
		REPEAT
	}

	protected void init(long ni) {
	}

	public void setLocalMatrix(Matrix matrix) {}

	protected void copyLocalMatrix(Shader dest) {}


	/* teslalinux: Shader additions */
	public boolean getLocalMatrix(Matrix localM) { return false; }
}
