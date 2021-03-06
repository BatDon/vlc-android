/*****************************************************************************
 * VideosProvider.kt
 *****************************************************************************
 * Copyright © 2018 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc.viewmodels

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModel
import android.arch.lifecycle.ViewModelProvider
import android.arch.lifecycle.ViewModelProviders
import android.support.v4.app.Fragment
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.withContext
import org.videolan.medialibrary.Medialibrary
import org.videolan.medialibrary.interfaces.MediaAddedCb
import org.videolan.medialibrary.media.MediaWrapper
import org.videolan.vlc.R
import org.videolan.vlc.VLCApplication
import org.videolan.vlc.media.MediaGroup
import org.videolan.vlc.util.Util

open class VideosProvider(private val group: String?, private val minGroupLen: Int, customSort : Int) : MedialibraryModel<MediaWrapper>(), MediaAddedCb {

    override fun canSortByDuration() = true
    override fun canSortByLastModified() = true
    private val res by lazy { VLCApplication.getAppResources() }

    private val thumbObs = Observer<MediaWrapper> { media -> updateActor.offer(MediaUpdate(listOf(media!!))) }

    init {
        if (customSort != Medialibrary.SORT_DEFAULT) sort = customSort
        Medialibrary.lastThumb.observeForever(thumbObs)
    }

    override fun onMediaAdded(mediaList: Array<out MediaWrapper>?) {
        if (!Util.isArrayEmpty<MediaWrapper>(mediaList)) updateActor.offer(MediaListAddition(mediaList!!.filter { it.type == MediaWrapper.TYPE_VIDEO }))
    }

    override fun onMediaUpdated(mediaList: Array<out MediaWrapper>?) {
        if (!Util.isArrayEmpty<MediaWrapper>(mediaList)) updateActor.offer(MediaUpdate(mediaList!!.filter { it.type == MediaWrapper.TYPE_VIDEO }))
    }

    override suspend fun updateList() {
        dataset.value = withContext(CommonPool) {
            val list = medialibrary.getVideos(sort, desc)
            val displayList = mutableListOf<MediaWrapper>()
            when {
                group !== null -> {
                    val loGroup = group.toLowerCase()
                    for (item in list) {
                        val title = item.title.toLowerCase().let { if (it.startsWith("the")) it.substring(4) else it }
                        if (title.startsWith(loGroup)) displayList.add(item)
                    }
                }
                minGroupLen > 0 -> MediaGroup.group(list, minGroupLen).mapTo(displayList) {
                    if (it.size() > 1 && res !== null) { it.description = res.getQuantityString(R.plurals.videos_quantity, it.size(), it.size()) }
                    it.media
                }
                else -> displayList.addAll(list)
            }
            displayList
        }
    }

    override fun onMedialibraryReady() {
        super.onMedialibraryReady()
        medialibrary.setMediaUpdatedCb(this, Medialibrary.FLAG_MEDIA_UPDATED_VIDEO)
        medialibrary.setMediaAddedCb(this, Medialibrary.FLAG_MEDIA_ADDED_VIDEO)
    }

    override fun onCleared() {
        super.onCleared()
        medialibrary.removeMediaAddedCb()
        medialibrary.removeMediaUpdatedCb()
        Medialibrary.lastThumb.removeObserver(thumbObs)
    }

    class Factory(val group: String?, private val minGroupLen : Int, private val sort : Int): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return VideosProvider(group, minGroupLen, sort) as T
        }
    }

    companion object {
        fun get(fragment: Fragment, group: String?, minGroupLen : Int, sort : Int) : VideosProvider {
            return ViewModelProviders.of(fragment, Factory(group, minGroupLen, sort)).get(VideosProvider::class.java)
        }
    }
}
