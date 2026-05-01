/* Copyright 2019 Tusky Contributors
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package com.keylesspalace.tusky.components.compose.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.keylesspalace.tusky.R
import com.keylesspalace.tusky.components.compose.alttext.AltTextGenerator
import com.keylesspalace.tusky.components.instanceinfo.InstanceInfoRepository.Companion.DEFAULT_MEDIA_DESCRIPTION_LIMIT
import com.keylesspalace.tusky.databinding.DialogImageDescriptionBinding
import com.keylesspalace.tusky.util.getParcelableCompat
import com.keylesspalace.tusky.util.hide
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CaptionDialog : DialogFragment() {
    private lateinit var listener: Listener
    private lateinit var binding: DialogImageDescriptionBinding
    private var animatable: Animatable? = null
    private var generationJob: Job? = null

    @Inject lateinit var altTextGenerator: AltTextGenerator

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val localId = arguments?.getInt(LOCAL_ID_ARG) ?: error("Missing localId")
        val inset = requireContext().resources.getDimensionPixelSize(R.dimen.dialog_inset)
        return MaterialAlertDialogBuilder(requireContext())
            .setView(createView(savedInstanceState))
            .setBackgroundInsetTop(inset)
            .setBackgroundInsetEnd(inset)
            .setBackgroundInsetBottom(inset)
            .setBackgroundInsetStart(inset)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                listener.onUpdateDescription(localId, binding.imageDescriptionText.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun createView(savedInstanceState: Bundle?): View {
        binding = DialogImageDescriptionBinding.inflate(layoutInflater)
        val imageView = binding.imageDescriptionView
        imageView.maxZoom = 6f
        val imageDescriptionText = binding.imageDescriptionText
        imageDescriptionText.post {
            imageDescriptionText.requestFocus()
            imageDescriptionText.setSelection(imageDescriptionText.length())
        }

        binding.imageDescriptionText.hint = getString(R.string.hint_describe_for_visually_impaired)
        binding.imageDescriptionText.setText(arguments?.getString(EXISTING_DESCRIPTION_ARG))

        savedInstanceState?.getCharSequence(DESCRIPTION_KEY)?.let {
            binding.imageDescriptionText.setText(it)
        }
        val descriptionLimit = arguments?.getInt(DESCRIPTION_LIMIT_ARG) ?: DEFAULT_MEDIA_DESCRIPTION_LIMIT
        binding.imageDescriptionLayout.counterMaxLength = descriptionLimit

        isCancelable = false
        dialog?.setCanceledOnTouchOutside(false)

        val previewUri = arguments?.getParcelableCompat<Uri>(PREVIEW_URI_ARG) ?: error("Preview Uri is null")
        val isImage = arguments?.getBoolean(IS_IMAGE_ARG, false) ?: false

        if (isImage && altTextGenerator.isConfigured()) {
            binding.generateAltTextContainer.visibility = View.VISIBLE
            binding.generateAltTextButton.setOnClickListener {
                startGeneration(previewUri)
            }
        }

        Glide.with(this)
            .load(previewUri)
            .downsample(DownsampleStrategy.CENTER_INSIDE)
            .into(object : CustomTarget<Drawable>(4096, 4096) {
                override fun onLoadCleared(placeholder: Drawable?) {
                    imageView.setImageDrawable(placeholder)
                }

                override fun onResourceReady(
                    resource: Drawable,
                    transition: Transition<in Drawable>?
                ) {
                    if (resource is Animatable) {
                        resource.callback = object : Drawable.Callback {
                            override fun invalidateDrawable(who: Drawable) {
                                imageView.invalidate()
                            }
                            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                                imageView.postDelayed(what, `when`)
                            }
                            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                                imageView.removeCallbacks(what)
                            }
                        }
                        resource.start()
                        animatable = resource
                    }
                    imageView.setImageDrawable(resource)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    super.onLoadFailed(errorDrawable)
                    imageView.hide()
                }
            })
        return binding.root
    }

    private fun startGeneration(uri: Uri) {
        if (generationJob?.isActive == true) return
        val alertDialog = dialog as AlertDialog
        val okButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancelButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        val originalCancelText = cancelButton.text

        binding.generateAltTextButton.visibility = View.GONE
        binding.generateAltTextProgress.visibility = View.VISIBLE
        okButton.isEnabled = false
        cancelButton.text = getString(R.string.action_cancel_generation)
        cancelButton.setOnClickListener { generationJob?.cancel() }

        generationJob = lifecycleScope.launch {
            try {
                val result = altTextGenerator.generate(uri)
                result.onSuccess { binding.imageDescriptionText.setText(it) }
                    .onFailure { showError(it) }
            } catch (_: CancellationException) {
                // user cancelled — silent, just restore UI
            } finally {
                if (isAdded) restoreUi(okButton, cancelButton, originalCancelText)
            }
        }
    }

    private fun showError(throwable: Throwable) {
        val msg = when (throwable) {
            is AltTextGenerator.NotConfiguredException ->
                getString(R.string.error_alt_text_not_configured)
            is AltTextGenerator.ImageLoadException ->
                getString(R.string.error_alt_text_load_image)
            is AltTextGenerator.NetworkException ->
                getString(R.string.error_alt_text_network)
            is AltTextGenerator.ServerHttpException ->
                getString(R.string.error_alt_text_server, throwable.code)
            is AltTextGenerator.UpstreamErrorException ->
                throwable.message ?: getString(R.string.error_alt_text_network)
            is AltTextGenerator.EmptyResponseException ->
                getString(R.string.error_alt_text_no_text)
            else -> throwable.message ?: getString(R.string.error_alt_text_network)
        }
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }

    private fun restoreUi(
        okButton: android.widget.Button,
        cancelButton: android.widget.Button,
        originalCancelText: CharSequence
    ) {
        binding.generateAltTextProgress.visibility = View.GONE
        binding.generateAltTextButton.visibility = View.VISIBLE
        okButton.isEnabled = (binding.imageDescriptionText.text?.length ?: 0) <=
            binding.imageDescriptionLayout.counterMaxLength
        cancelButton.text = originalCancelText
        attachDefaultCancelHandler(cancelButton)
    }

    private fun attachDefaultCancelHandler(cancelButton: android.widget.Button) {
        cancelButton.setOnClickListener {
            if (arguments?.getString(EXISTING_DESCRIPTION_ARG).orEmpty() !=
                binding.imageDescriptionText.text.toString()
            ) {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.confirm_dismiss_caption)
                    .setPositiveButton(R.string.yes) { _, _ -> dialog?.dismiss() }
                    .setNegativeButton(R.string.no, null)
                    .show()
            } else {
                dialog?.dismiss()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val descriptionLimit = arguments?.getInt(DESCRIPTION_LIMIT_ARG) ?: DEFAULT_MEDIA_DESCRIPTION_LIMIT
        binding.imageDescriptionText.doOnTextChanged { newText, _, _, _ ->
            (dialog as AlertDialog?)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
                (newText?.length ?: 0) <= descriptionLimit && generationJob?.isActive != true
        }
        dialog?.apply {
            window?.setLayout(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        attachDefaultCancelHandler((dialog as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putCharSequence(DESCRIPTION_KEY, binding.imageDescriptionText.text)
        super.onSaveInstanceState(outState)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener ?: error("Activity is not ComposeCaptionDialog.Listener")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        generationJob?.cancel()
        animatable?.stop()
        (animatable as? Drawable?)?.callback = null
    }

    interface Listener {
        fun onUpdateDescription(localId: Int, description: String)
    }

    companion object {
        fun newInstance(
            localId: Int,
            existingDescription: String?,
            previewUri: Uri,
            descriptionLimit: Int,
            isImage: Boolean
        ) = CaptionDialog().apply {
            arguments = bundleOf(
                LOCAL_ID_ARG to localId,
                EXISTING_DESCRIPTION_ARG to existingDescription,
                PREVIEW_URI_ARG to previewUri,
                DESCRIPTION_LIMIT_ARG to descriptionLimit,
                IS_IMAGE_ARG to isImage
            )
        }

        private const val DESCRIPTION_KEY = "description"
        private const val EXISTING_DESCRIPTION_ARG = "existing_description"
        private const val PREVIEW_URI_ARG = "preview_uri"
        private const val LOCAL_ID_ARG = "local_id"
        private const val DESCRIPTION_LIMIT_ARG = "description_limit"
        private const val IS_IMAGE_ARG = "is_image"
    }
}
