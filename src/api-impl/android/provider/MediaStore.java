package android.provider;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;

public class MediaStore {

	public static class Images {

		public static class Media {

			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/images/media");
			public static final Uri INTERNAL_CONTENT_URI = Uri.parse("content://media/internal/images/media");
		}

		public static class Thumbnails {

			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/images/media");

			public static Cursor queryMiniThumbnail(ContentResolver contentResolver, long id, int kind, String[] projection) {
				return null;
			}

			public static Bitmap getThumbnail(ContentResolver contentResolver, long imageId, long groupId, int kind, BitmapFactory.Options options) throws IOException {
				Uri uri = Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(String.valueOf(imageId)).build();
				try (ParcelFileDescriptor fd = contentResolver.openFileDescriptor(uri, "r")) {
					return BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, options);
				}
			}
		}
	}

	public static class Video {

		public static class Media {

			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/video/media");
			public static final Uri INTERNAL_CONTENT_URI = Uri.parse("content://media/internal/video/media");
		}

		public static class Thumbnails {

			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/video/media");

			public static Bitmap getThumbnail(ContentResolver contentResolver, long videoId, int kind, BitmapFactory.Options options) throws IOException {
				Uri uri = Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(String.valueOf(videoId)).build();
				try (ParcelFileDescriptor fd = contentResolver.openFileDescriptor(uri, "r")) {
					return BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, options);
				}
			}
		}
	}

	public static class Audio {

		public static class Media {

			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/audio/media");
		}

		public static class Artists {
			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/audio/artists");
		}

		public static class Albums {
			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/audio/albums");
		}

		public static class Genres {
			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/audio/genres");
		}
	}

	public static class Files {

		public static Uri getContentUri(String type) {
			return Uri.parse("content://media/files/" + type);
		}
	}

	/** AOSP: the query parameter that asks for the original media (location metadata not redacted). */
	private static final String PARAM_REQUIRE_ORIGINAL = "requireOriginal";

	/** AOSP: the URI asking for the original, unredacted media. */
	public static Uri setRequireOriginal(Uri uri) {
		return uri.buildUpon().appendQueryParameter(PARAM_REQUIRE_ORIGINAL, "1").build();
	}

	/** AOSP: whether setRequireOriginal was applied to the URI. */
	public static boolean getRequireOriginal(Uri uri) {
		return "1".equals(uri.getQueryParameter(PARAM_REQUIRE_ORIGINAL));
	}

	/** AOSP: the most images the photo picker lets a user select (MediaProvider's fixed limit). */
	public static int getPickImagesMaxLimit() {
		return 100;
	}

	/** AOSP (hidden): opens the media file through the resolver. */
	public static android.content.res.AssetFileDescriptor openAssetFileDescriptor(ContentResolver resolver, Uri uri,
	                                                                               String mode, android.os.CancellationSignal signal)
	    throws FileNotFoundException {
		return resolver.openAssetFileDescriptor(uri, mode, signal);
	}
}
