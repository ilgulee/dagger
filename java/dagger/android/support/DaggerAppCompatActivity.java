/*
 * Copyright (C) 2017 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.android.support;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v7.app.AppCompatActivity;
import dagger.android.AndroidInjection;
import dagger.android.DispatchingAndroidInjector;
import dagger.internal.Beta;
import javax.inject.Inject;

/**
 * An {@link AppCompatActivity} that injects its members in {@link #onCreate(Bundle)} and can be
 * used to inject {@link Fragment}s attached to it.
 */
@Beta
public abstract class DaggerAppCompatActivity extends AppCompatActivity
    implements HasDispatchingFragmentInjector {

  @Inject DispatchingAndroidInjector<Fragment> fragmentInjector;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    AndroidInjection.inject(this);
  }

  @Override
  public DispatchingAndroidInjector<Fragment> fragmentInjector() {
    return fragmentInjector;
  }
}