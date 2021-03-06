/*
 * *************************************************************************
 *  MediaBrowserFragment.java
 * **************************************************************************
 *  Copyright © 2015-2016 VLC authors and VideoLAN
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.browser;

import android.annotation.TargetApi;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.view.ActionMode;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import org.videolan.libvlc.util.AndroidUtil;
import org.videolan.medialibrary.Medialibrary;
import org.videolan.medialibrary.media.MediaLibraryItem;
import org.videolan.medialibrary.media.MediaWrapper;
import org.videolan.vlc.R;
import org.videolan.vlc.VLCApplication;
import org.videolan.vlc.gui.AudioPlayerContainerActivity;
import org.videolan.vlc.gui.ContentActivity;
import org.videolan.vlc.gui.InfoActivity;
import org.videolan.vlc.gui.audio.BaseAudioBrowser;
import org.videolan.vlc.gui.helpers.UiTools;
import org.videolan.vlc.gui.helpers.hf.WriteExternalDelegate;
import org.videolan.vlc.gui.video.VideoGridFragment;
import org.videolan.vlc.gui.view.ContextMenuRecyclerView;
import org.videolan.vlc.gui.view.SwipeRefreshLayout;
import org.videolan.vlc.interfaces.Filterable;
import org.videolan.vlc.util.AndroidDevices;
import org.videolan.vlc.util.FileUtils;
import org.videolan.vlc.util.Permissions;
import org.videolan.vlc.util.WorkersKt;
import org.videolan.vlc.viewmodels.BaseModel;

import java.util.LinkedList;

public abstract class MediaBrowserFragment<T extends BaseModel> extends Fragment implements android.support.v7.view.ActionMode.Callback, Filterable {

    public final static String TAG = "VLC/MediaBrowserFragment";

    public View mSearchButtonView;
    protected SwipeRefreshLayout mSwipeRefreshLayout;
    protected Medialibrary mMediaLibrary;
    protected ActionMode mActionMode;
    public FloatingActionButton mFabPlay;
    protected T mProvider;

    public T getProvider() {
        return mProvider;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMediaLibrary = VLCApplication.getMLInstance();
        setHasOptionsMenu(true);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mSwipeRefreshLayout != null) mSwipeRefreshLayout.setColorSchemeResources(R.color.orange700);
            mFabPlay = getActivity().findViewById(R.id.fab);
    }

    public void onStart() {
        super.onStart();
        updateActionBar();
        if (mFabPlay != null) {
            setFabPlayVisibility(true);
            mFabPlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onFabPlayClick(v);
                }
            });
        }
    }

    public void updateActionBar() {
        final AppCompatActivity activity = (AppCompatActivity)getActivity();
        if (activity == null) return;
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(getTitle());
            activity.getSupportActionBar().setSubtitle(getSubTitle());
            activity.supportInvalidateOptionsMenu();
        }
        if (activity instanceof ContentActivity) ((ContentActivity)activity).toggleAppBarElevation(!(this instanceof BaseAudioBrowser));
    }

    @Override
    public void onPause() {
        super.onPause();
        stopActionMode();
    }

    public void setFabPlayVisibility(boolean enable) {
        if (mFabPlay != null) mFabPlay.setVisibility(enable ? View.VISIBLE : View.GONE);
    }

    public void onFabPlayClick(View view) {}

    public abstract String getTitle();
    public abstract void onRefresh();

    protected String getSubTitle() { return null; }
    public void clear() {}

    protected void inflate(Menu menu, int position) {}
    protected void setContextMenuItems(Menu menu, int position) {}
    protected boolean handleContextItemSelected(MenuItem menu, int position) { return false;}

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        if (menuInfo == null) return;
        final ContextMenuRecyclerView.RecyclerContextMenuInfo info = (ContextMenuRecyclerView.RecyclerContextMenuInfo)menuInfo;
        inflate(menu, info.position);
        setContextMenuItems(menu, info.position);
    }

    @Override
    public boolean onContextItemSelected(MenuItem menu) {
        if (!getUserVisibleHint()) return false;
        final ContextMenuRecyclerView.RecyclerContextMenuInfo info = (ContextMenuRecyclerView.RecyclerContextMenuInfo) menu.getMenuInfo();
        return info != null && handleContextItemSelected(menu, info.position);
    }

    protected void deleteMedia(final MediaLibraryItem mw, final boolean refresh, final Runnable failCB) {
        WorkersKt.runBackground(new Runnable() {
            @Override
            public void run() {
                final LinkedList<String> foldersToReload = new LinkedList<>();
                final LinkedList<String> mediaPaths = new LinkedList<>();
                for (MediaWrapper media : mw.getTracks()) {
                    final String path = media.getUri().getPath();
                    final String parentPath = FileUtils.getParent(path);
                    if (FileUtils.deleteFile(media.getUri())) {
                        if (media.getId() > 0L && !foldersToReload.contains(parentPath)) {
                            foldersToReload.add(parentPath);
                        }
                        mediaPaths.add(media.getLocation());
                    } else onDeleteFailed(media);
                }
                for (String folder : foldersToReload) mMediaLibrary.reload(folder);
                if (getActivity() != null) {
                    WorkersKt.runOnMainThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mediaPaths.isEmpty()) {
                                if (failCB != null) failCB.run();
                                return;
                            }
                            // TODO
//                            if (mService != null) for (String path : mediaPaths) mService.removeLocation(path);
                            if (refresh) onRefresh();
                        }
                    });
                }
            }
        });
    }

    protected boolean checkWritePermission(MediaWrapper media, Runnable callback) {
        final Uri uri = media.getUri();
        if (!"file".equals(uri.getScheme())) return false;
        if (uri.getPath().startsWith(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY)) {
            //Check write permission starting Oreo
            if (AndroidUtil.isOOrLater && !Permissions.canWriteStorage()) {
                Permissions.askWriteStoragePermission(getActivity(), false, callback);
                return false;
            }
        } else if (AndroidUtil.isLolliPopOrLater && WriteExternalDelegate.Companion.needsWritePermission(uri)) {
            WriteExternalDelegate.Companion.askForExtWrite(getActivity(), uri, callback);
            return false;
        }
        return true;
    }

    private void onDeleteFailed(MediaWrapper media) {
        final View v = getView();
        if (v != null && isAdded()) UiTools.snacker(v, getString(R.string.msg_delete_failed, media.getTitle()));
    }

    protected void showInfoDialog(MediaLibraryItem item) {
        final Intent i = new Intent(getActivity(), InfoActivity.class);
        i.putExtra(InfoActivity.TAG_ITEM, item);
        startActivity(i);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        menu.findItem(R.id.ml_menu_sortby).setVisible(getProvider().canSortByName());
        menu.findItem(R.id.ml_menu_sortby_artist_name).setVisible(getProvider().canSortByArtist());
        menu.findItem(R.id.ml_menu_sortby_album_name).setVisible(getProvider().canSortByAlbum());
        menu.findItem(R.id.ml_menu_sortby_length).setVisible(getProvider().canSortByDuration());
        menu.findItem(R.id.ml_menu_sortby_date).setVisible(getProvider().canSortByReleaseDate() || getProvider().canSortByLastModified());
        menu.findItem(R.id.ml_menu_sortby_number).setVisible(false);
        UiTools.updateSortTitles(this);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.ml_menu_sortby_name:
                sortBy(Medialibrary.SORT_ALPHA);
                return true;
            case R.id.ml_menu_sortby_length:
                sortBy(Medialibrary.SORT_DURATION);
                return true;
            case R.id.ml_menu_sortby_date:
                sortBy(this instanceof VideoGridFragment ? mMediaLibrary.SORT_LASTMODIFICATIONDATE : Medialibrary.SORT_RELEASEDATE);
                return true;
            case R.id.ml_menu_sortby_artist_name:
                sortBy(Medialibrary.SORT_ARTIST);
                return true;
            case R.id.ml_menu_sortby_album_name:
                sortBy(Medialibrary.SORT_ALBUM);
                return true;
            case R.id.ml_menu_sortby_number:
                sortBy(Medialibrary.SORT_FILESIZE); //TODO
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    protected void sortBy(int sort) {
        getProvider().sort(sort);
    }

    public Menu getMenu() {
        final AudioPlayerContainerActivity activity = (AudioPlayerContainerActivity) getActivity();
        if (activity == null) return null;
        return activity.getMenu();

    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public void startActionMode() {
        final AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity == null) return;
        mActionMode = activity.startSupportActionMode(this);
        setFabPlayVisibility(false);
    }

    protected void stopActionMode() {
        if (mActionMode != null) {
            mActionMode.finish();
            onDestroyActionMode(mActionMode);
            setFabPlayVisibility(true);
        }
    }

    public void invalidateActionMode() {
        if (mActionMode != null) mActionMode.invalidate();
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public void filter(String query) {
        getProvider().filter(query);
    }

    public void restoreList() {
        getProvider().restore();
    }

    @Override
    public boolean enableSearchOption() {
        return true;
    }

    @Override
    public void setSearchVisibility(boolean visible) {
        UiTools.setViewVisibility(mSearchButtonView, visible ? View.VISIBLE : View.GONE);
    }
}
