package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;

import java.io.File;
import java.util.regex.Pattern;

public class LocalLoginFragment extends Fragment {
    public static final String TAG = "LOCAL_LOGIN_FRAGMENT";

    private EditText mUsernameEditText;

    public LocalLoginFragment(){
        super(R.layout.fragment_local_login);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mUsernameEditText = view.findViewById(R.id.login_edit_email);
        mUsernameEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                String str = charSequence.toString();

                if(!str.isEmpty()) {
                    if (str.contains(" ")) {
                        str = str.replace(" ", "");
                        mUsernameEditText.setText(str);
                    }
                    if (str.length() > 16) {
                        str = str.substring(0, 16);
                        mUsernameEditText.setText(str);
                    }
                    Pattern pattern = Pattern.compile("[^a-zA-Z0-9_]");
                    if(pattern.matcher(str).find()) {
                        StringBuilder newStr = new StringBuilder();
                        for (char c : str.toCharArray()) {
                            if (!pattern.matcher(String.valueOf(c)).find()) newStr.append(c);
                        }
                        str = newStr.toString();
                        mUsernameEditText.setText(str);
                    }

                    mUsernameEditText.setSelection(str.length());
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
                String text = mUsernameEditText.getText().toString();

                if(text.isEmpty() || text.length() < 3) mUsernameEditText.setError(getText(R.string.small_username));
                else if(new File(Tools.DIR_ACCOUNT_NEW + "/" + text + ".json").exists()) mUsernameEditText.setError(getText(R.string.exists_username));
                else mUsernameEditText.setError(null);
            }
        });
        view.findViewById(R.id.login_button).setOnClickListener(v -> {
            //if(!checkEditText()) return;
            if(mUsernameEditText.getError() != null) return;

            ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{
                    mUsernameEditText.getText().toString(), "" });

            //Tools.swapFragment(requireActivity(), MainMenuFragment.class, MainMenuFragment.TAG, false, null);
        });
    }


    /** @return Whether the mail (and password) text are eligible to make an auth request  */
    private boolean checkEditText(){

        String text = mUsernameEditText.getText().toString();

        return !(text.isEmpty()
                || text.length() < 3
                || text.length() > 16
                || !text.matches("\\w+")
                || new File(Tools.DIR_ACCOUNT_NEW + "/" + text + ".json").exists()
        );
    }
}
