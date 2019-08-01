package com.raywenderlich.android.redditclone

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Transformations.map
import android.arch.lifecycle.Transformations.switchMap
import android.arch.lifecycle.ViewModel
import com.raywenderlich.android.redditclone.networking.RedditPost
import com.raywenderlich.android.redditclone.utils.Listing

class MainViewModel(repository: MainRepository) : ViewModel() {

    private val subredditName = MutableLiveData<String>()
    private val repoResult: LiveData<Listing<RedditPost>> = map(subredditName) {
        repository.postsOfSubreddit(subredditName)
    }

    val posts = switchMap(repoResult) { it.pagedList }!!
    val networkState = switchMap(repoResult) { it.networkState }!!
    val refreshState = switchMap(repoResult) { it.refreshState }!!

    fun retry() {
        val listing = repoResult?.value
        listing?.retry?.invoke()
    }

    fun refresh() {
        repoResult.value?.refresh?.invoke()
    }

    fun showSubreddit(subreddit: String): Boolean {
        if (subredditName.value == subreddit) {
            return false
        }
        subredditName.value = subreddit
        return true
    }

}