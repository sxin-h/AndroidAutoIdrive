package me.hufman.androidautoidrive.music.controllers

import android.os.Handler
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import me.hufman.androidautoidrive.music.*
import java.util.*
import kotlin.coroutines.CoroutineContext

/**
 * Given a list of Connectors to try, connect to the given MusicAppInfo
 * The Connectors should be sorted to have the most suitable connector at the end of the list
 * For example, a MediaSession controller before the SpotifyAppController
 * This is because the SpotifyAppController can provide better Metadata
 */
class CombinedMusicAppController(val handler: Handler, connectors: List<MusicAppController.Connector>, val appInfo: MusicAppInfo): MusicAppController, CoroutineScope {
	override val coroutineContext: CoroutineContext
		get() = handler.asCoroutineDispatcher("CombinedMusicAppController")

	// Wait up to this time for all the connectors to connect, before doing a browse/search
	val CONNECTION_TIMEOUT = 5000

	private var browseJob: Job? = null
	private var searchJob: Job? = null

	// remember the last controller that we browsed or searched through
	private var browseableController: MusicAppController? = null

	var callback: ((MusicAppController) -> Unit)? = null
	val controllers = connectors.reversed().map {
		it.connect(appInfo).also { pendingController ->
			pendingController.subscribe { freshController ->
				// a controller has connected/disconnected
				if (freshController != null) {
					callback?.invoke(freshController)
					freshController.subscribe { controller ->
						// a controller wants to notify the UI
						callback?.invoke(controller)
					}
				}
			}
		}
	}


	/**
	 * Runs the given command against the first working of the connected controllers
	 */
	private inline fun withController(f: (MusicAppController) -> Unit) {
		for (pendingController in controllers) {
			val controller = pendingController.value ?: continue
			try {
				f(controller)
				break
			} catch (e: UnsupportedOperationException) {
				// this controller doesn't support it, try the next one
			} catch (e: Exception) {
				// error running the command against this controller, try the next one
				// maybe disconnect it from future attempts, or to reconnect
			}
		}
	}

	/**
	 * Iterates through the controllers to return some data from the first that responds
	 */
	private inline fun <R> getFromController(f: (MusicAppController) -> R): R? {
		for (pendingController in controllers) {
			val controller = pendingController.value ?: continue
			try {
				return f(controller)
			} catch (e: UnsupportedOperationException) {
				// this controller doesn't support it, try the next one
			} catch (e: Exception) {
				// error running the command against this controller, try the next one
				// maybe disconnect it from future attempts, or to reconnect
			}
		}
		// no controller responded, return null
		return null
	}

	fun isPending(): Boolean {
		return controllers.any {
			it.pending
		}
	}

	fun isConnected(): Boolean {
		return controllers.any {
			it.value != null
		}
	}

	override fun play() = withController {
		if (!it.isSupportedAction(MusicAction.PLAY)) {
			throw UnsupportedOperationException()
		}
		it.play()
	}

	override fun pause() = withController {
		if (!it.isSupportedAction(MusicAction.PAUSE)) {
			throw UnsupportedOperationException()
		}
		it.pause()
	}

	override fun skipToPrevious() = withController {
		if (!it.isSupportedAction(MusicAction.SKIP_TO_PREVIOUS)) {
			throw UnsupportedOperationException()
		}
		it.skipToPrevious()
	}

	override fun skipToNext() = withController {
		if (!it.isSupportedAction(MusicAction.SKIP_TO_NEXT)) {
			throw UnsupportedOperationException()
		}
		it.skipToNext()
	}

	override fun seekTo(newPos: Long) = withController {
		if (!it.isSupportedAction(MusicAction.SEEK_TO)) {
			throw UnsupportedOperationException()
		}
		it.seekTo(newPos)
	}

	override fun playSong(song: MusicMetadata) {
		// this command plays a given browse or searched song, so use the last-found browseable controller
		browseableController?.playSong(song)
	}

	override fun playQueue(song: MusicMetadata) = withController {
		if (it.getQueue().isEmpty()) {
			throw UnsupportedOperationException()
		}
		it.playQueue(song)
	}

	override fun playFromSearch(search: String) = withController {
		if (!it.isSupportedAction(MusicAction.PLAY_FROM_SEARCH)) {
			throw UnsupportedOperationException()
		}
		it.playFromSearch(search)
	}

	override fun customAction(action: CustomAction) = withController {
		if (!it.getCustomActions().contains(action)) {
			throw UnsupportedOperationException()
		}
		it.customAction(action)
	}

	override fun getQueue(): List<MusicMetadata> {
		return getFromController {
			it.getQueue()
		} ?: LinkedList()
	}

	override fun getMetadata(): MusicMetadata? {
		return getFromController {
			it.getMetadata()
		}
	}

	override fun getPlaybackPosition(): PlaybackPosition {
		return getFromController {
			it.getPlaybackPosition()
		} ?: PlaybackPosition(true, 0, 0, 0)
	}

	override fun isSupportedAction(action: MusicAction): Boolean {
		return controllers.any {
			it.value?.isSupportedAction(action) == true
		}
	}

	override fun getCustomActions(): List<CustomAction> {
		return getFromController {
			it.getCustomActions()
		} ?: LinkedList()
	}

	suspend fun waitforConnect() {
		if (isPending()) {
			for (i in 0..10) {
				delay(CONNECTION_TIMEOUT / 10L)
				if (!isPending()) {
					break
				}
			}
		}
	}

	override fun browseAsync(directory: MusicMetadata?): Deferred<List<MusicMetadata>> {
		// always resume browsing from the previous controller that we were browsing
		val browseableController = this.browseableController
		if (directory != null && browseableController != null) {
			return browseableController.browseAsync(directory)
		}

		browseJob?.cancel()
		return async {
			var results: List<MusicMetadata> = LinkedList()
			browseJob = launch {
				waitforConnect()
				Log.i("MusicAppController", "Finished connecting $appInfo")
				// try to find a browseable controller
				for (pendingController in controllers) {
					val controller = pendingController.value ?: continue
					val pendingResults = controller.browseAsync(directory)
					// detect instantly-empty results
					if (pendingResults.isCompleted) {
						results = pendingResults.await()
						if (results.isEmpty()) {
							continue
						}
					}
					// wait for the results
					results = pendingResults.await()
					// make the Play and Browse commands dig through the browse results
					this@CombinedMusicAppController.browseableController = controller
					break
				}
			}
			browseJob?.join()
			results
		}
	}

	override fun searchAsync(query: String): Deferred<List<MusicMetadata>?> {
		searchJob?.cancel()
		return async {
			var results: List<MusicMetadata>? = null
			searchJob = launch {
				waitforConnect()
				// try to find a browseable controller
				for (pendingController in controllers) {
					val controller = pendingController.value ?: continue
					val pendingResults = controller.searchAsync(query)
					// detect instantly-empty results
					if (pendingResults.isCompleted) {
						results = pendingResults.await()
						if (results == null) {
							continue
						}
					}
					// wait for the results
					results = pendingResults.await()
					// make the Play and Browse commands dig through the search results
					this@CombinedMusicAppController.browseableController = controller
				}
			}
			searchJob?.join()
			results
		}
	}

	override fun subscribe(callback: (MusicAppController) -> Unit) {
		this.callback = callback
	}

	override fun disconnect() {
		this.callback = null
		controllers.forEach {
			try {
				it.value?.disconnect()
			} catch (e: Exception) {}
		}
	}
}