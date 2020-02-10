package me.hufman.androidautoidrive.music.controllers

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.ContentApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import me.hufman.androidautoidrive.MutableObservable
import me.hufman.androidautoidrive.Observable
import me.hufman.androidautoidrive.music.*
import me.hufman.androidautoidrive.music.PlaybackPosition
import java.lang.Exception
import java.util.*


class SpotifyAppController(val remote: SpotifyAppRemote): MusicAppController {
	companion object {
		const val TAG = "SpotifyAppController"
		const val REDIRECT_URI = "me.hufman.androidautoidrive://spotify_callback"

		fun MusicMetadata.Companion.fromSpotify(track: Track, coverArt: Bitmap? = null): MusicMetadata {
			// general track metadata
			return MusicMetadata(mediaId = track.uri, queueId = track.uri.hashCode().toLong(),
					title = track.name, album = track.album.name, artist = track.artist.name, coverArt = coverArt)
		}
		fun MusicMetadata.Companion.fromSpotify(listItem: ListItem, coverArt: Bitmap? = null): MusicMetadata {
			// browse result
			return MusicMetadata(mediaId = listItem.uri, title = listItem.title, subtitle = listItem.subtitle,
					playable = listItem.playable, browseable = listItem.hasChildren, coverArt = coverArt)
		}
		fun MusicMetadata.toListItem(): ListItem {
			return ListItem(this.mediaId, this.mediaId, null, this.title, this.subtitle,
					this.playable, this.browseable)
		}

		fun CustomAction.Companion.fromSpotify(name: String): CustomAction {
			return CustomAction("com.spotify.music", name, name, null, null)
		}
	}

	class Connector(val context: Context): MusicAppController.Connector {
		override fun connect(appInfo: MusicAppInfo): Observable<MusicAppController> {
			val pendingController = MutableObservable<MusicAppController>()
			if (appInfo.packageName != "com.spotify.music") {
				pendingController.value = null
				return pendingController
			}

			Log.w(TAG, "Attempting to connect to Spotify Remote")

			val CLIENT_ID = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
					.metaData.getString("com.spotify.music.API_KEY", "unavailable")
			val params = ConnectionParams.Builder(CLIENT_ID)
					.setRedirectUri(REDIRECT_URI)
					.showAuthView(true)
					.build()


			val remoteListener = object: com.spotify.android.appremote.api.Connector.ConnectionListener {
				override fun onFailure(e: Throwable?) {
					Log.e(TAG, "Failed to connect to Spotify Remote: $e")
					pendingController.value = null
				}

				override fun onConnected(remote: SpotifyAppRemote?) {
					if (remote != null) {
						Log.i(TAG, "Successfully connected to Spotify Remote")
						pendingController.value = SpotifyAppController(remote)
					} else {
						Log.e(TAG, "Connected to a null Spotify Remote?")
						pendingController.value = null
					}
				}
			}

			SpotifyAppRemote.connect(context, params, remoteListener)
			return pendingController
		}
	}

	// create the Custom Actions during client creation, so that the language is loaded
	val CUSTOM_ACTION_TURN_SHUFFLE_ON = CustomAction.fromSpotify("TURN_SHUFFLE_ON")
	val CUSTOM_ACTION_TURN_SHUFFLE_OFF = CustomAction.fromSpotify("TURN_SHUFFLE_OFF")
	val CUSTOM_ACTION_TURN_REPEAT_ALL_ON = CustomAction.fromSpotify("TURN_REPEAT_ALL_ON")
	val CUSTOM_ACTION_TURN_REPEAT_ONE_ON = CustomAction.fromSpotify("TURN_REPEAT_ONE_ON")
	val CUSTOM_ACTION_TURN_REPEAT_ONE_OFF = CustomAction.fromSpotify("TURN_REPEAT_ONE_OFF")
	val CUSTOM_ACTION_ADD_TO_COLLECTION = CustomAction.fromSpotify("ADD_TO_COLLECTION")
	val CUSTOM_ACTION_REMOVE_FROM_COLLECTION = CustomAction.fromSpotify("REMOVE_FROM_COLLECTION")

	// Spotify is very asynchronous, save any subscription state for the getters
	var callback: ((MusicAppController) -> Unit)? = null    // UI listener
	val spotifySubscription: Subscription<PlayerState> = remote.playerApi.subscribeToPlayerState()
	val playlistSubscription: Subscription<PlayerContext> = remote.playerApi.subscribeToPlayerContext()
	var playerActions: PlayerRestrictions? = null
	var playerOptions: PlayerOptions? = null
	var currentTrack: MusicMetadata? = null
	var position: PlaybackPosition = PlaybackPosition(true, 0, 0, -1)
	var currentTrackLibrary: Boolean? = null
	var queueUri: String? = null
	var queueItems: List<MusicMetadata>? = null

	init {
		spotifySubscription.setEventCallback { playerState ->
			Log.d(TAG, "Heard an update from Spotify")

			// update the available actions
			playerActions = playerState.playbackRestrictions
			playerOptions = playerState.playbackOptions

			// update the current track info
			val track = playerState.track
			if (track != null) {
				currentTrack = MusicMetadata.fromSpotify(track)
				// try to load the coverart
				val coverArtLoader = remote.imagesApi.getImage(track.imageUri)
				coverArtLoader.setResultCallback { coverArt ->
					currentTrack = MusicMetadata.fromSpotify(track, coverArt = coverArt)
				}
				currentTrackLibrary = null
				remote.userApi.getLibraryState(track.uri).setResultCallback {
					currentTrackLibrary = it.isAdded
				}
			} else {
				currentTrack = null
			}

			// update a progress bar
			position = PlaybackPosition(playerState.isPaused, lastPosition = playerState.playbackPosition, maximumPosition = playerState.track.duration)

			callback?.invoke(this)
		}

		playlistSubscription.setEventCallback { playerContext ->
			// update the current queue
			val uri = playerContext.uri
			queueUri = uri
			if (uri != null) {
				val listItem = ListItem(uri, uri, null, playerContext.title, playerContext.subtitle, false, true)
				remote.contentApi.getChildrenOfItem(listItem, 100, 0).setResultCallback { currentQueue ->
					if (currentQueue != null) {
						queueItems = currentQueue.items.mapIndexed { index: Int, listItem: ListItem? ->
							MusicMetadata(queueId = listItem?.uri?.hashCode()?.toLong(), title = listItem?.title ?: "Track $index")
						}
					} else {
						queueItems = LinkedList()
					}
				}
			}

			callback?.invoke(this)
		}
	}

	override fun play() {
		remote.playerApi.resume()
	}

	override fun pause() {
		remote.playerApi.pause()
	}

	override fun skipToPrevious() {
		remote.playerApi.skipPrevious()
	}

	override fun skipToNext() {
		remote.playerApi.skipNext()
	}

	override fun seekTo(newPos: Long) {
		remote.playerApi.seekTo(newPos)
	}

	override fun playSong(song: MusicMetadata) {
		remote.playerApi.play(song.mediaId)
	}

	override fun playQueue(song: MusicMetadata) {
		// queue item equality is based on queueId, which we are storing as the uri hashCode
		// so, we have to iterate through the queue to find the user's selected queueId
		val queueUri = this.queueUri
		if (song.queueId != null) {
			queueItems?.forEachIndexed { index, it ->
				if (it.queueId == song.queueId) {
					remote.playerApi.skipToIndex(queueUri, index)
				}
			}
		}
	}

	override fun playFromSearch(search: String) {
	}

	override fun customAction(action: CustomAction) {
		when (action) {
			CUSTOM_ACTION_TURN_SHUFFLE_ON -> remote.playerApi.setShuffle(true)
			CUSTOM_ACTION_TURN_SHUFFLE_OFF -> remote.playerApi.setShuffle(false)
			CUSTOM_ACTION_TURN_REPEAT_ALL_ON -> remote.playerApi.setRepeat(Repeat.ALL)
			CUSTOM_ACTION_TURN_REPEAT_ONE_ON -> remote.playerApi.setRepeat(Repeat.ONE)
			CUSTOM_ACTION_TURN_REPEAT_ONE_OFF -> remote.playerApi.setRepeat(Repeat.OFF)
			CUSTOM_ACTION_ADD_TO_COLLECTION -> currentTrack?.mediaId?.let { remote.userApi.addToLibrary(it) }
			CUSTOM_ACTION_REMOVE_FROM_COLLECTION -> currentTrack?.mediaId?.let { remote.userApi.removeFromLibrary(it) }
		}
	}

	override fun getQueue(): List<MusicMetadata> {
		return queueItems ?: LinkedList()
	}

	override fun getMetadata(): MusicMetadata? {
		return currentTrack
	}

	override fun getPlaybackPosition(): PlaybackPosition {
		return position
	}

	override fun isSupportedAction(action: MusicAction): Boolean {
		val playerActions = playerActions
		return when (action) {
			MusicAction.SKIP_TO_PREVIOUS -> playerActions?.canSkipPrev == true
			MusicAction.SKIP_TO_NEXT -> playerActions?.canSkipNext == true
			MusicAction.PLAY -> true
			MusicAction.PAUSE -> true
			MusicAction.SEEK_TO -> playerActions?.canSeek == true
			// figure out search
			else -> false
		}
	}

	override fun getCustomActions(): List<CustomAction> {
		val actions = LinkedList<CustomAction>()
		if (playerActions?.canToggleShuffle == true) {
			if (playerOptions?.isShuffling == true) actions.add(CUSTOM_ACTION_TURN_SHUFFLE_OFF)
			if (playerOptions?.isShuffling == false) actions.add(CUSTOM_ACTION_TURN_SHUFFLE_ON)
		}
		if (playerOptions?.repeatMode == Repeat.OFF) {
			if (playerActions?.canRepeatContext == true) actions.add(CUSTOM_ACTION_TURN_REPEAT_ALL_ON)
			else if (playerActions?.canRepeatTrack == true) actions.add(CUSTOM_ACTION_TURN_REPEAT_ONE_ON)
		}
		if (playerOptions?.repeatMode == Repeat.ALL) {
			if (playerActions?.canRepeatTrack == true) actions.add(CUSTOM_ACTION_TURN_REPEAT_ONE_ON)
			else actions.add(CUSTOM_ACTION_TURN_REPEAT_ONE_OFF)
		}
		if (playerOptions?.repeatMode == Repeat.ONE) actions.add(CUSTOM_ACTION_TURN_REPEAT_ONE_OFF)
		if (currentTrackLibrary == false) actions.add(CUSTOM_ACTION_ADD_TO_COLLECTION)
		if (currentTrackLibrary == true) actions.add(CUSTOM_ACTION_REMOVE_FROM_COLLECTION)
		return actions
	}

	override fun browseAsync(directory: MusicMetadata?): Deferred<List<MusicMetadata>> {
		val deferred = CompletableDeferred<List<MusicMetadata>>()
		val result = if (directory?.mediaId == null) remote.contentApi.getRecommendedContentItems("default-cars")
		else remote.contentApi.getChildrenOfItem(directory.toListItem(), 200, 0)
		result.setResultCallback { results ->
			deferred.complete(results.items.map {
				MusicMetadata.fromSpotify(it)
			})
		}
		return deferred
	}

	override fun searchAsync(query: String): Deferred<List<MusicMetadata>?> {
		// requires a Web API call, not sure how to get the access token
		return CompletableDeferred(null)
	}

	override fun subscribe(callback: (MusicAppController) -> Unit) {
		this.callback = callback
	}

	override fun disconnect() {
		this.callback = null
		try {
			spotifySubscription.cancel()
		} catch (e: Exception) {
			Log.w(TAG, "Exception while disconnecting from Spotify: $e")
		}
		try {
			playlistSubscription.cancel()
		} catch (e: Exception) {
			Log.w(TAG, "Exception while disconnecting from Spotify: $e")
		}
		try {
			SpotifyAppRemote.disconnect(remote)
		} catch (e: Exception) {
			Log.w(TAG, "Exception while disconnecting from Spotify: $e")
		}
	}

	override fun toString(): String {
		return "SpotifyAppController"
	}
}