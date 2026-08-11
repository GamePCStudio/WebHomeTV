package com.fongmi.android.tv.ui.dialog;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogAudioSettingBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.effect.audio.AudioEffectPreset;
import com.fongmi.android.tv.setting.AudioSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.chip.Chip;

public final class AudioSettingDialog extends BaseBottomSheetDialog {

    private DialogAudioSettingBinding binding;
    private PlayerManager player;

    public static AudioSettingDialog create() {
        return new AudioSettingDialog();
    }

    public AudioSettingDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogAudioSettingBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setupPresets();
        setupEnable();
        updateUnsupported();
    }

    private void setupPresets() {
        String[] names = ResUtil.getStringArray(R.array.audio_preset_names);
        for (int preset = 0; preset < names.length; preset++) {
            Chip chip = new Chip(requireContext());
            chip.setText(names[preset]);
            chip.setId(preset);
            binding.presetGroup.addView(chip);
        }
        binding.presetGroup.check(getAppliedPreset());
        binding.presetGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (!checkedIds.isEmpty()) applyPreset(checkedIds.get(0));
            else binding.presetGroup.check(getAppliedPreset());
        });
    }

    private int getAppliedPreset() {
        return AudioSetting.isEnabled() ? AudioSetting.getPreset() : AudioEffectPreset.OFF;
    }

    private void applyPreset(int preset) {
        AudioSetting.putPreset(preset);
        binding.enable.setChecked(preset != AudioEffectPreset.OFF);
        apply();
    }

    private void setupEnable() {
        binding.enable.setChecked(AudioSetting.isEnabled());
        binding.enable.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                if (AudioSetting.getPreset() == AudioEffectPreset.OFF) AudioSetting.putPreset(AudioEffectPreset.NATURAL);
            } else {
                AudioSetting.putPreset(AudioEffectPreset.OFF);
            }
            binding.presetGroup.check(getAppliedPreset());
            apply();
        });
    }

    private void updateUnsupported() {
        boolean supported = player != null && !player.isReleased() && player.canSetAudioSetting();
        binding.unsupported.setVisibility(supported ? GONE : VISIBLE);
        binding.enable.setEnabled(supported);
        binding.presetGroup.setEnabled(supported);
    }

    private void apply() {
        if (player != null && !player.isReleased()) player.applyAudioSetting();
    }
}