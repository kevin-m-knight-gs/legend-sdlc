// Copyright 2021 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.sdlc.server.gitlab.auth;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.finos.legend.sdlc.server.auth.Session;
import org.finos.legend.sdlc.server.gitlab.GitLabAppInfo;

public class GitLabAuthorizerManager
{
    private final ImmutableList<GitLabAuthorizer> gitLabAuthorizers;

    private GitLabAuthorizerManager(ImmutableList<GitLabAuthorizer> gitLabAuthorizers)
    {
        this.gitLabAuthorizers = gitLabAuthorizers.notEmpty() ? gitLabAuthorizers : Lists.immutable.with(new KerberosGitLabAuthorizer());
    }

    public static GitLabAuthorizerManager newManager(GitLabAuthorizer... gitLabAuthorizers)
    {
        return newManager(Lists.immutable.with(gitLabAuthorizers));
    }

    public static GitLabAuthorizerManager newManager(Iterable<? extends GitLabAuthorizer> gitLabAuthorizers)
    {
        return new GitLabAuthorizerManager(Lists.immutable.withAll(gitLabAuthorizers));
    }

    public GitLabToken authorize(Session session, GitLabAppInfo appInfo)
    {
        for (GitLabAuthorizer gitLabAuthorizer : this.gitLabAuthorizers)
        {
            GitLabToken token = gitLabAuthorizer.authorize(session, appInfo);
            if (token != null)
            {
                return token;
            }
        }
        return null;
    }
}
