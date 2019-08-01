package com.raywenderlich.android.redditclone

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Transformations
import android.arch.paging.DataSource
import android.arch.paging.LivePagedListBuilder
import android.arch.paging.PagedList
import android.support.annotation.MainThread
import android.util.Log
import com.raywenderlich.android.redditclone.database.RedditDb
import com.raywenderlich.android.redditclone.networking.RedditApiResponse
import com.raywenderlich.android.redditclone.networking.RedditPost
import com.raywenderlich.android.redditclone.networking.RedditService
import com.raywenderlich.android.redditclone.utils.Listing
import com.raywenderlich.android.redditclone.utils.NetworkState
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.Executor

class MainRepository(private val redditApi: RedditService,
                     private val db: RedditDb,
                     private val ioExecutor: Executor) {

    private val config = PagedList.Config.Builder()
            .setPageSize(30)
            .setEnablePlaceholders(false)
            .build()

    /**
     * Inserts the response into the database while also assigning position indices to items.
     */
    private fun insertResultIntoDb(body: List<RedditPost>?) {
        body.let { posts ->
            db.runInTransaction{
                val start = db.postDao().getNextIndexInSubreddit()
                val items = posts?.mapIndexed{ index, post ->
                    post.indexInResponse = start + index
                    post
                }
                items?.let { db.postDao().insert(it) }
            }

        }
    }

    /**
     * Returns a Listing for the given subreddit.
     */
    @MainThread
    fun postsOfSubreddit(subredditName: MutableLiveData<String>): Listing<RedditPost> {
        // create a boundary callback which will observe when the user reaches to the edges of
        // the list and update the database with extra data.
        val boundaryCallback = RedditBoundaryCallback(
                api = redditApi,
                handleResponse = this::insertResultIntoDb,
                executor = ioExecutor)
        // we are using a mutable live data to trigger refresh requests which eventually calls
        // refresh method and gets a new live data. Each refresh request by the user becomes a newly
        // dispatched data in refreshTrigger
        val refreshTrigger = MutableLiveData<Unit>()
        val refreshState = Transformations.switchMap(refreshTrigger) {
            refresh()
        }

        // We use toLiveData Kotlin extension function here, you could also use LivePagedListBuilder
        val livePagedList =  initializedPagedListBuilder( db.postDao().posts(),boundaryCallback).build()

        return Listing(
                pagedList = livePagedList,
                networkState = boundaryCallback.networkState,
                retry = {
                    boundaryCallback.helper.retryAllFailed()
                },
                refresh = {
                    refreshTrigger.value = null
                },
                refreshState = refreshState
        )
    }

    private fun initializedPagedListBuilder(source: DataSource.Factory<Int, RedditPost>, boundaryCallback: RedditBoundaryCallback):
            LivePagedListBuilder<Int, RedditPost> {
        val livePageListBuilder = LivePagedListBuilder(
                source,
                config)
        livePageListBuilder.setBoundaryCallback(boundaryCallback)
        return livePageListBuilder
    }


    @MainThread
    private fun refresh(): LiveData<NetworkState> {
        val networkState = MutableLiveData<NetworkState>()
        networkState.value = NetworkState.LOADING
        redditApi.getPosts()
                .enqueue(object : Callback<RedditApiResponse> {
                    override fun onFailure(call: Call<RedditApiResponse>?, t: Throwable) {
                        Log.e("RedditBoundaryCallback", "Failed to load data!")
                        // retrofit calls this on main thread so safe to call set value
                        networkState.value = NetworkState.error(t.message)
                    }

                    override fun onResponse(
                            call: Call<RedditApiResponse>?,
                            response: Response<RedditApiResponse>) {
                        //4
                        val posts = response.body()?.data?.children?.map { it.data }
                        ioExecutor.execute {
                            db.runInTransaction {
                                db.postDao().deleteBySubreddit()
                                insertResultIntoDb(posts)
                            }
                            // since we are in bg thread now, post the result.
                            networkState.postValue(NetworkState.LOADED)
                        }
                    }
                })
        return networkState
    }


}