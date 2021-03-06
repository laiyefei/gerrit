// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.comment;

import static com.google.gerrit.entities.Patch.COMMIT_MSG;
import static com.google.gerrit.entities.Patch.MERGE_LIST;
import static java.util.stream.Collectors.groupingBy;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Comment;
import com.google.gerrit.entities.CommentContext;
import com.google.gerrit.entities.Project;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.common.ContextLineInfo;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.patch.ComparisonType;
import com.google.gerrit.server.patch.Text;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Computes the list of {@link ContextLineInfo} for a given comment, that is, the lines of the
 * source file surrounding and including the area where the comment was written.
 */
public class CommentContextLoader {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final GitRepositoryManager repoManager;
  private final Project.NameKey project;

  public interface Factory {
    CommentContextLoader create(Project.NameKey project);
  }

  @Inject
  CommentContextLoader(GitRepositoryManager repoManager, @Assisted Project.NameKey project) {
    this.repoManager = repoManager;
    this.project = project;
  }

  /**
   * Load the comment context for multiple comments at once. This method will open the repository
   * and read the source files for all necessary comments' file paths.
   *
   * @param comments a list of comments.
   * @return a Map where all entries consist of the input comments and the values are their
   *     corresponding {@link CommentContext}.
   */
  public Map<Comment, CommentContext> getContext(Iterable<Comment> comments) throws IOException {
    ImmutableMap.Builder<Comment, CommentContext> result =
        ImmutableMap.builderWithExpectedSize(Iterables.size(comments));

    // Group comments by commit ID so that each commit is parsed only once
    Map<ObjectId, List<Comment>> commentsByCommitId =
        Streams.stream(comments).collect(groupingBy(Comment::getCommitId));

    try (Repository repo = repoManager.openRepository(project);
        RevWalk rw = new RevWalk(repo)) {
      for (ObjectId commitId : commentsByCommitId.keySet()) {
        RevCommit commit = rw.parseCommit(commitId);
        for (Comment comment : commentsByCommitId.get(commitId)) {
          Optional<Range> range = getStartAndEndLines(comment);
          if (!range.isPresent()) {
            result.put(comment, CommentContext.empty());
            continue;
          }
          String filePath = comment.key.filename;
          switch (filePath) {
            case COMMIT_MSG:
              result.put(
                  comment, getContextForCommitMessage(rw.getObjectReader(), commit, range.get()));
              break;
            case MERGE_LIST:
              result.put(
                  comment, getContextForMergeList(rw.getObjectReader(), commit, range.get()));
              break;
            default:
              result.put(comment, getContextForFilePath(repo, rw, commit, filePath, range.get()));
          }
        }
      }
      return result.build();
    }
  }

  private CommentContext getContextForCommitMessage(
      ObjectReader reader, RevCommit commit, Range range) throws IOException {
    Text text = Text.forCommit(reader, commit);
    return createContext(text, range);
  }

  private CommentContext getContextForMergeList(ObjectReader reader, RevCommit commit, Range range)
      throws IOException {
    ComparisonType cmp = ComparisonType.againstParent(1);
    Text text = Text.forMergeList(cmp, reader, commit);
    return createContext(text, range);
  }

  private CommentContext getContextForFilePath(
      Repository repo, RevWalk rw, RevCommit commit, String filePath, Range range)
      throws IOException {
    // TODO(ghareeb): We can further group the comments by file paths to avoid opening
    // the same file multiple times.
    try (TreeWalk tw = TreeWalk.forPath(rw.getObjectReader(), filePath, commit.getTree())) {
      if (tw == null) {
        logger.atWarning().log(
            "Could not find path %s in the git tree of ID %s.", filePath, commit.getTree().getId());
        return CommentContext.empty();
      }
      ObjectId id = tw.getObjectId(0);
      Text src = new Text(repo.open(id, Constants.OBJ_BLOB));
      return createContext(src, range);
    }
  }

  private static CommentContext createContext(Text src, Range range) {
    if (range.start() < 1 || range.end() > src.size()) {
      throw new StorageException(
          "Invalid comment range " + range + ". Text only contains " + src.size() + " lines.");
    }
    ImmutableMap.Builder<Integer, String> context =
        ImmutableMap.builderWithExpectedSize(range.end() - range.start());
    for (int i = range.start(); i < range.end(); i++) {
      context.put(i, src.getString(i - 1));
    }
    return CommentContext.create(context.build());
  }

  private static Optional<Range> getStartAndEndLines(Comment comment) {
    if (comment.range != null) {
      return Optional.of(Range.create(comment.range.startLine, comment.range.endLine + 1));
    } else if (comment.lineNbr > 0) {
      return Optional.of(Range.create(comment.lineNbr, comment.lineNbr + 1));
    }
    return Optional.empty();
  }

  @AutoValue
  abstract static class Range {
    static Range create(int start, int end) {
      return new AutoValue_CommentContextLoader_Range(start, end);
    }

    /** Start line of the comment (inclusive). */
    abstract int start();

    /** End line of the comment (exclusive). */
    abstract int end();
  }
}
