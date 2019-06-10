package com.github.oheger.locationteller

import android.os.Bundle
import android.support.v4.app.Fragment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

/**
 * A simple [Fragment] subclass.
 * Activities that contain this fragment must implement the
 * [TrackFragment.OnFragmentInteractionListener] interface
 * to handle interaction events.
 * Use the [TrackFragment.newInstance] factory method to
 * create an instance of this fragment.
 *
 */
class TrackFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.i("TrackFragment", "Creating TrackFragment")
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_track, container, false)
    }
}
