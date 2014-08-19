package com.github.feribg.audiogetter.ui.fragments;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.github.feribg.audiogetter.R;
import com.github.feribg.audiogetter.config.App;
import com.github.feribg.audiogetter.config.Constants;
import com.github.feribg.audiogetter.helpers.Utils;
import com.github.feribg.audiogetter.models.Download;
import com.github.feribg.audiogetter.models.SearchItem;
import com.github.feribg.audiogetter.services.ManagerService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koushikdutta.async.future.Future;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches and displays Vimeo search results via the Vimeo HTTP API
 *
 * @see <a href="https://developer.vimeo.com/api/endpoints/videos#">VIMEO HTTP API (video section)</a>
 */
public class VimeoSearchResultsFragment extends SearchResultsBaseFragment {


    //keep track of the current page
    Integer page = 1;
    //if it has more pages to load
    Boolean hasMore = true;
    Future<JsonObject> searchResultsFuture;

    @Override
    public View onCreateView(final LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.search_list, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }


    @Override
    public void onSaveInstanceState(Bundle savedState) {
        super.onSaveInstanceState(savedState);
        Log.d(App.TAG, "SAVING:" + searchTerm);
        savedState.putParcelableArrayList("results", results);
        savedState.putString("searchTerm", searchTerm);
        savedState.putInt("page", page);
        savedState.putBoolean("hasMore", hasMore);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void cleanup() {
        super.cleanup();
        hasMore = true;
        page = 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void loadMore() {
        try {
            // don't attempt to load more if a load is already in progress
            if (searchResultsFuture != null && !searchResultsFuture.isDone() && !searchResultsFuture.isCancelled())
                return;
            // we have loaded the max number of results, dont load more
            if (!hasMore) {
                if (searchFooterView != null) {
                    getListView().removeFooterView(searchFooterView);
                }
                return;
            }

            URI uri = Utils.getVimeoSearchURI(searchTerm, page);
            searchResultsFuture = Ion.with(this)
                    .load(uri.toString())
                    .setHeader("Authorization", "bearer " + Constants.Vimeo.API_TOKEN)
                    .asJsonObject()
                    .setCallback(new FutureCallback<JsonObject>() {
                        @Override
                        public void onCompleted(Exception e, JsonObject json) {
                            try {
                                if (e != null) {
                                    throw e;
                                }
                                if (json == null) {
                                    throw new Exception("Server returned a null response");
                                }
                                if (json.get("total").getAsInt() == 0) {
                                    Toast.makeText(getActivity(), R.string.error_no_results, Toast.LENGTH_LONG).show();
                                    return;
                                }
                                JsonArray videos = json.get("data").getAsJsonArray();
                                for (JsonElement object : videos) {
                                    SearchItem item = sourceController.extractVimeoSearchItem(object.getAsJsonObject());
                                    if (item != null) {
                                        searchResultsAdapter.add(item);
                                    }
                                }
                                if (json.get("paging").getAsJsonObject().get("next").isJsonNull()) {
                                    hasMore = false;
                                } else {
                                    hasMore = true;
                                    page++;
                                }
                            } catch (Exception ex) {
                                Toast.makeText(getActivity(), R.string.error_loading_results, Toast.LENGTH_LONG).show();
                                Log.e(App.TAG, "Error while trying to get search results", ex);
                            }

                        }
                    });
        } catch (Exception e) {
            Log.e(App.TAG, "There was an error loading search results", e);
            Toast.makeText(getActivity(), R.string.error_loading_results, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Called on activity change state to load the previous results and page states
     *
     * @param savedInstanceState the current activity saved state
     */
    @Override
    protected void loadState(Bundle savedInstanceState) {
        super.loadState(savedInstanceState);
        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey("hasMore")) {
                hasMore = savedInstanceState.getBoolean("hasMore");
            }
            if (savedInstanceState.containsKey("page")) {
                page = savedInstanceState.getInt("page");
            }
        }
    }

    /**
     * Show the confirmation for downloading a file
     *
     * @param download the download object to send to the downloader service
     */
    @Override
    protected void showConfirmation(final Download download) {
        String msgText = String.format(getResources().getString(R.string.search_download_confirm_message), download.getTitle());

        //Ask the user if they want to quit
        new AlertDialog.Builder(getActivity())
                .setIcon(android.R.drawable.ic_dialog_info)
                .setTitle(R.string.long_download_title)
                .setMessage(msgText)
                .setPositiveButton(R.string.confirm_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            List<NameValuePair> qparams = new ArrayList<NameValuePair>();
                            qparams.add(new BasicNameValuePair("url", download.getUrl()));
                            URI uri = Utils.getUri(Constants.Backend.API_SCHEME, "/api/info", qparams);
                            Ion.with(App.ctx)
                                    .load(uri.toString())
                                    .asJsonObject()
                                    .setCallback(new FutureCallback<JsonObject>() {
                                        @Override
                                        public void onCompleted(Exception e, JsonObject result) {
                                            try {
                                                Download dl = sourceController.extractData(result);
                                                if (dl.getDst() != null && dl.getDst().exists()) {
                                                    Toast.makeText(App.ctx, "This file already exists in your library", Toast.LENGTH_LONG).show();
                                                } else {
                                                    Intent i = new Intent(ManagerService.INTENT_DOWNLOAD);
                                                    i.putExtra("download", dl);
                                                    getActivity().sendBroadcast(i);
                                                    Log.d(App.TAG, "download broadcast was sent");
                                                }
                                            } catch (Exception ex) {
                                                Toast.makeText(App.ctx, "There was an error trying to download this file", Toast.LENGTH_LONG).show();
                                                Log.e(App.TAG, "Error while trying to fetch video data", e);
                                            }
                                        }
                                    });

                        } catch (Exception ex) {
                            Toast.makeText(App.ctx, "There was an error trying to download this file", Toast.LENGTH_LONG).show();
                            Log.e(App.TAG, "Error while trying to fetch video data", ex);
                        }
                    }
                })
                .setNegativeButton(R.string.confirm_cancel, null)
                .show();
    }
}
