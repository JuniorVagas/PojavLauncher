package net.kdt.pojavlaunch.fragments;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

public class SelectAuthFragment extends Fragment {
    public static final String TAG = "AUTH_SELECT_FRAGMENT";

    public SelectAuthFragment(){
        super(R.layout.fragment_select_auth_method);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mMicrosoftButton = view.findViewById(R.id.button_microsoft_authentication);
        Button mLocalButton = view.findViewById(R.id.button_local_authentication);
        TextView loginProblem = view.findViewById(R.id.login_problem_textview);

        mMicrosoftButton.setOnClickListener(v -> Tools.swapFragment(requireActivity(), MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG, null));
        mLocalButton.setOnClickListener(v -> {
            AlertDialog.Builder loginSolve = new AlertDialog.Builder(requireActivity());
            loginSolve.setTitle(R.string.universal_warn);
            loginSolve.setMessage(R.string.login_local_warn);
            loginSolve.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> Tools.swapFragment(requireActivity(), LocalLoginFragment.class, LocalLoginFragment.TAG, null));
            loginSolve.setNeutralButton(R.string.discord_support_title, (dialogInterface, i) -> Tools.showDiscordSupport(requireActivity()));
            loginSolve.create().show();
        });
        loginProblem.setOnClickListener(v -> {
            AlertDialog.Builder loginSolve = new AlertDialog.Builder(requireActivity());
            loginSolve.setTitle(R.string.login_problem_title);
            loginSolve.setMessage(R.string.login_problem_message);
            loginSolve.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> dialogInterface.dismiss());
            loginSolve.setNeutralButton(R.string.discord_support_title, (dialogInterface, i) -> Tools.showDiscordSupport(requireActivity()));
            loginSolve.create().show();
        });
    }
}
