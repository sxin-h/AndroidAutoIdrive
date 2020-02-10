package me.hufman.androidautoidrive.music.controllers

import android.content.Context
import android.os.DeadObjectException
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlinx.coroutines.*
import me.hufman.androidautoidrive.music.*
import java.util.*

/**
 * Wraps a MediaController with a handy interface
 * Any function may throw DeadObjectException, please catch it
 */
class GenericMusicAppController(val context: Context, val mediaController: MediaControllerCompat, val musicBrowser: MusicBrowser?) : MusicAppController {
	// forward any callbacks to the UI
	val controllerCallback = object: MediaControllerCompat.Callback() {
		override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
			callback?.invoke(this@GenericMusicAppController)
		}

		override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
			callback?.invoke(this@GenericMusicAppController)
		}

		override fun onQueueChanged(queue: MutableList<MediaSessionCompat.QueueItem>?) {
			callback?.invoke(this@GenericMusicAppController)
		}
	}
	var callback: ((MusicAppController) -> Unit)? = null    // UI listener

	@Throws(DeadObjectException::class)
	override fun play() {
		mediaController.transportControls.play()
	}

	@Throws(DeadObjectException::class)
	override fun pause() {
		mediaController.transportControls.pause()
	}

	@Throws(DeadObjectException::class)
	override fun skipToPrevious() {
		mediaController.transportControls.skipToPrevious()
	}

	@Throws(DeadObjectException::class)
	override fun skipToNext() {
		mediaController.transportControls.skipToNext()
	}

	@Throws(DeadObjectException::class)
	override fun seekTo(newPos: Long) {
		mediaController.transportControls.seekTo(newPos)
	}

	@Throws(DeadObjectException::class)
	override fun playSong(song: MusicMetadata) {
		if (song.mediaId != null) {
			mediaController.transportControls.playFromMediaId(song.mediaId, song.extras)
		}
	}

	@Throws(DeadObjectException::class)
	override fun playQueue(song: MusicMetadata) {
		if (song.queueId != null) {
			mediaController.transportControls.skipToQueueItem(song.queueId)
		}
	}

	@Throws(DeadObjectException::class)
	override fun playFromSearch(search: String) {
		mediaController.transportControls.playFromSearch(search, null)
	}

	override fun customAction(action: CustomAction) {
		if (action.packageName == mediaController.packageName) {
			mediaController.transportControls?.sendCustomAction(action.action, action.extras)
		}
	}

	/* Current state */
	@Throws(DeadObjectException::class)
	override fun getQueue(): List<MusicMetadata> {
		return mediaController.queue.map { MusicMetadata.fromQueueItem(it) }
	}

	@Throws(DeadObjectException::class)
	override fun getMetadata(): MusicMetadata? {
		return mediaController.metadata?.let {
			MusicMetadata.fromMediaMetadata(it, mediaController.playbackState)
		}
	}

	@Throws(DeadObjectException::class)
	override fun getPlaybackPosition(): PlaybackPosition {
		val state = mediaController.playbackState
		return if (state == null) {
			PlaybackPosition(true, 0, 0, 0)
		} else {
			val metadata = getMetadata()
			val isPaused = (
					state.state == PlaybackStateCompat.STATE_PAUSED ||
					state.state == PlaybackStateCompat.STATE_CONNECTING ||
					state.state == PlaybackStateCompat.STATE_BUFFERING
					)
			PlaybackPosition(isPaused, state.lastPositionUpdateTime, state.position, metadata?.duration ?: -1)
		}
	}

	@Throws(DeadObjectException::class)
	override fun isSupportedAction(action: MusicAction): Boolean {
		return ((mediaController.playbackState?.actions ?: 0) and action.flag) > 0
	}

	@Throws(DeadObjectException::class)
	override fun getCustomActions(): List<CustomAction> {
		return mediaController.playbackState?.customActions?.map {
			CustomAction.fromMediaCustomAction(context, mediaController.packageName, it)
		} ?: LinkedList()
	}

	override fun browseAsync(directory: MusicMetadata?): Deferred<List<MusicMetadata>> {
		val app = musicBrowser
		return GlobalScope.async {
			val results = app?.browse(directory?.mediaId)?.map {
				MusicMetadata.fromMediaItem(it)
			} ?: LinkedList()
			results
		}
	}

	override fun searchAsync(query: String): Deferred<List<MusicMetadata>?> {
		val app = musicBrowser
		return GlobalScope.async {
			app?.search(query)?.map {
				MusicMetadata.fromMediaItem(it)
			}
		}
	}

	override fun subscribe(callback: (MusicAppController) -> Unit) {
		this.callback = callback
		mediaController.registerCallback(this.controllerCallback)
	}

	override fun disconnect() {
		try {
			mediaController.unregisterCallback(this.controllerCallback)
			this.callback = null
		} catch (e: Exception) {}
		try {
			musicBrowser?.disconnect()
		} catch (e: Exception) {}
	}

	override fun toString(): String {
		return "GenericMusicAppController(${mediaController.packageName},${musicBrowser?.connected})"
	}
}