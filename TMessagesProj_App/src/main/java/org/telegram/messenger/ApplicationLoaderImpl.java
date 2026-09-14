package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

import tw.nekomimi.nekogram.Extra;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    protected boolean isStandalone() {
        return Extra.isDirectApp();
    }

}
