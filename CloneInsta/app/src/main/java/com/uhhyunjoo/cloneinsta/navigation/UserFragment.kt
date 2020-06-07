package com.uhhyunjoo.cloneinsta.navigation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.uhhyunjoo.cloneinsta.R
import com.uhhyunjoo.cloneinsta.navigation.model.ContentDTO
import kotlinx.android.synthetic.main.fragment_user.view.*

class UserFragment :Fragment(){
    var fragmentview : View? = null
    var firestore : FirebaseFirestore? = null
    var uid : String? = null
    var auth : FirebaseAuth? = null
    override fun onCreateView(

        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        fragmentview = LayoutInflater.from(activity).inflate(R.layout.fragment_user, container, false)
        uid = arguments?.getString("destinationUid")
        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()
        fragmentview?.account_recyclerview?.adapter = UserFragmentRecyclerViewAdapter()
        fragmentview?.account_recyclerview?.layoutManager = GridLayoutManager(activity!!, 3)
        return fragmentview
    }
    inner class UserFragmentRecyclerViewAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var contentDTOs : ArrayList<ContentDTO> = arrayListOf()
        init{
            firestore?.collection("images")?.whereEqualTo("uids", uid)?.addSnapshotListener { querySnapshot, firebaseFirestoreException ->
                if(querySnapshot == null) return@addSnapshotListener

                // Get data
                for(snapshot in querySnapshot.documents){
                    contentDTOs.add(snapshot.toObject(ContentDTO::class.java)!!)
                }
                fragmentview?.account_tv_post_count?.text = contentDTOs.size.toString()
                notifyDataSetChanged()
            }

        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            var width = resources.displayMetrics.widthPixels / 3
            var imageview = ImageView(parent.context)
            imageview.layoutParams = LinearLayoutCompat.LayoutParams(width, width)
            return CustomViewHolder(imageview)
        }

        inner class CustomViewHolder(var imageview: ImageView) : RecyclerView.ViewHolder(imageview) {

        }

        override fun getItemCount(): Int {
           return contentDTOs.size
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            var imageview = (holder as CustomViewHolder).imageview
            Glide.with(holder.itemView.context).load(contentDTOs[position]).apply(RequestOptions().centerCrop()).into(imageview)
        }
    }
}