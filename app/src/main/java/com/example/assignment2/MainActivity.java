package com.example.assignment2;

import android.Manifest;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.content.ContentResolver;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private RecyclerView recyclerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerView = findViewById(R.id.imageGallery);
        recyclerView.setHasFixedSize(true);
        int orientation = getResources().getConfiguration().orientation;

        ContentObserver contentObserver;
        contentObserver = new ContentObserver(new Handler()) { // Reloads the photos if a change is detected
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                display();
            }
        };

        getContentResolver().registerContentObserver( // Detects a change in images
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver
        );



        if (orientation == Configuration.ORIENTATION_LANDSCAPE) { // Checks orientation to see if it is landscape or not
            recyclerView.setLayoutManager(new GridLayoutManager(this, 6)); //If phone is landscape there will be 6 pictures per row
        } else {
            recyclerView.setLayoutManager(new GridLayoutManager(this, 4)); //If phone is not landscape there will be 4 pictures per row
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_MEDIA_IMAGES}, 1); // checks to see if permissions have been granted
        } else {
            display(); //If permission is granted the images are then displayed
        }

    }

    private void display() {
        final int maxCacheSize = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8); // Takes the max memory and converts it to kilobytes then allocates a 1/8 of the available memory
        LruCache<String, Bitmap> thumbnailCache = new LruCache<>(maxCacheSize); // Creates a cache of thumbnails
        List<Photo> photoList = fetchPhotos(); // populates the photo list with all the available photos
        MyAdapter myAdapter = new MyAdapter(photoList, thumbnailCache);
        recyclerView.setAdapter(myAdapter);
    }

    private List<Photo> fetchPhotos() {
        List<Photo> photos = new ArrayList<>(); // Creates a dynamic array for photos
        ContentResolver contentResolver = getContentResolver();
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        //Creates an array of data taken from each image
        String[] mediaStoreData = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.ORIENTATION,
        };

        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC"; // Sorts the images in descending order by their date
        try (Cursor cursor = contentResolver.query(uri, mediaStoreData, null, null, sortOrder)) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID); // Retrieves position of id column
                int orientationColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION); // Retrieves position of orientation column

                while (cursor.moveToNext()) { // iterates through each row of data collecting the id and orientation which are then placed into an instance of photo
                    long id = cursor.getLong(idColumn);
                    int orientation = cursor.getInt(orientationColumn);
                    String photoId = String.valueOf(id);
                    photos.add(new Photo(photoId, orientation));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return photos; // Returns a list of photo instances
    }

    public static class Photo { // This is the Photo class which stores image data
        private final String id;
        private final int orientation;

        public Photo(String id, int orientation) {
            this.id = id;
            this.orientation = orientation;

        }

        public String getId() {
            return id;
        }
        public int getOrientation() {
            return  orientation;
        }

    }

    public class MyAdapter extends RecyclerView.Adapter<MyAdapter.MyViewHolder> {
        private final List<Photo> mPhotos;
        private final LruCache<String, Bitmap> thumbnailCache;
        private final ExecutorService threadPool;

        public class MyViewHolder extends RecyclerView.ViewHolder { // Holds reference to the ui component of thr grid images
            public ImageView imageView;
            public MyViewHolder(ImageView v) {
                super(v);
                imageView = v.findViewById(R.id.grid_images);
            }
        }

        public MyAdapter(List<Photo> photos, LruCache<String, Bitmap> cache) { // A constructor
            this.mPhotos = photos;
            this.thumbnailCache = cache;
            this.threadPool = Executors.newWorkStealingPool();
        }

        @NonNull
        @Override
        public MyAdapter.MyViewHolder onCreateViewHolder(ViewGroup parent, int viewType) { // Creates a view holder for each item
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.photo_grid, parent, false);
            ImageView imageView = view.findViewById(R.id.grid_images);
            return new MyViewHolder(imageView);
        }


        @Override
        public void onBindViewHolder(@NonNull MyViewHolder holder, int position) { // populates the photo grid with data
            Photo photo = mPhotos.get(position);

            Bitmap cachedThumbnail = getBitmapFromCache(photo.getId()); // checks if a thumbnail is cached or not
            if (cachedThumbnail != null) {
                holder.imageView.setImageBitmap(cachedThumbnail);
            } else {
                threadPool.execute(() -> { // If the thumbnail is not cached a background thread is initiated to cache the image
                    try {
                        // Reads images metadata
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        InputStream is2 = getContentResolver().openInputStream(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo.getId()));
                        Bitmap bitmap = BitmapFactory.decodeStream(is2, null, options);
                        Bitmap thumbnail = ThumbnailUtils.extractThumbnail(bitmap, 100, 100);
                        // checks if the images rotation and corrects it if its not upright
                        if (photo.getOrientation() != 0) {
                            Matrix matrix = new Matrix();
                            matrix.postRotate(photo.getOrientation());
                            thumbnail = Bitmap.createBitmap(thumbnail, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), matrix, true);
                        }

                        addBitmapToCache(photo.getId(), thumbnail);
                        final Bitmap finalThumbnail = thumbnail;
                        holder.imageView.post(() -> holder.imageView.setImageBitmap(finalThumbnail)); // Image is posted into the ui thread
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
            // When an image is called it calls show high res image method to show a high resolution image
            holder.imageView.setOnClickListener(v -> {
                ((MainActivity) holder.imageView.getContext()).showHighResImage(photo.getId(), photo.getOrientation());
            });
        }

        @Override
        public int getItemCount() {
            return mPhotos.size();
        } //gets the amount of items in the mPhotos list

        private void addBitmapToCache(String key, Bitmap bitmap) { // Adds a bitmap to the cache: avoids duplication
            if (getBitmapFromCache(key) == null) { // Checks if bitmap is already in a cache
                thumbnailCache.put(key, bitmap); // Places bitmap inside cache
            }
        }

        private Bitmap getBitmapFromCache(String key) {
            return thumbnailCache.get(key);
        } // Retrieves a bitmap from the cache using a photo id as a key


    }

    private void showHighResImage(String imageId, int orientation) { // Used to display the high resolution image view
        Dialog dialog = new Dialog(this); // dialog created to hold image
        dialog.setContentView(R.layout.photo_view); // The layout for the dialog is defined which is the view used for the high res image
        ImageView imageView = dialog.findViewById(R.id.highResImage); // a reference to the view in the dialog

        try {
            InputStream is = getContentResolver().openInputStream(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId));
            Bitmap highResImage = BitmapFactory.decodeStream(is); // reads image meta data and places it inside highResImage
            if (orientation != 0) { // Checks if the high res image is orientated correctly and if not corrects it
                Matrix matrix = new Matrix();
                matrix.postRotate(orientation);
                highResImage = Bitmap.createBitmap(highResImage, 0, 0, highResImage.getWidth(), highResImage.getHeight(), matrix, true);
            }
            imageView.setImageBitmap(highResImage); // the bitmap of the high res image is set to the imageView
            if (is != null) { // input stream is closed
                is.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        dialog.show(); // The image is displayed to the user
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            display();
        }
    }



}
