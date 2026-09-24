package com.eottabom.migration.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 대상 프로젝트의 git 조작. */
public record Git(Path dir) {

	public String head() {
		String out = Processes.capture(this.dir, "git", "rev-parse", "HEAD");
		return (out != null) ? out.trim() : null;
	}

	/** 이 디렉토리를 patch 스냅샷 / 커밋 대상에서 뺀다 (.git/info/exclude). */
	public void exclude(String entry) {
		Path exclude = this.dir.resolve(".git/info/exclude");
		try {
			List<String> lines = Files.exists(exclude) ? Files.readAllLines(exclude) : List.of();
			if (!lines.contains(entry)) {
				Files.createDirectories(exclude.getParent());
				Files.writeString(exclude, (lines.isEmpty() ? "" : String.join("\n", lines) + "\n") + entry + "\n");
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** .gitignore 에 걸리지 않는 추적 안 된 파일. */
	public Set<String> untracked() {
		String out = Processes.capture(this.dir, "git", "ls-files", "--others", "--exclude-standard");
		return (out != null) ? new LinkedHashSet<>(out.lines().filter((l) -> !l.isBlank()).toList()) : Set.of();
	}

	/**
	 * base 커밋 대비 누적 변경을 patch 로 남긴다. 실제 인덱스는 건드리지 않는다. 추적 중인 파일의 변경과 created(레시피가 새로 만든
	 * 파일)만 담는다. 빌드/테스트가 만든 파일(.gitignore 누락)은 섞이지 않는다.
	 */
	public boolean diffSince(String base, Path patch, Collection<String> created, Path tempIndex) {
		// 사용자 인덱스(스테이징 상태)를 건드리지 않도록 임시 인덱스에서 스테이징하고 비교한다
		String tree = snapshotTree(created, tempIndex);
		return tree != null && Processes.run(this.dir, patch, List.of("git", "diff", "--binary", base, tree));
	}

	/** 줄바꿈/공백 차이(core.autocrlf 등)로 실패하면 공백을 무시하고 한 번 더 시도한다. */
	public boolean applyReverse(Path patch) {
		return run("git", "apply", "-R", "--binary", patch.toString())
				|| run("git", "apply", "-R", "--binary", "--ignore-whitespace", patch.toString());
	}

	public boolean apply(Path patch) {
		return run("git", "apply", "--binary", patch.toString())
				|| run("git", "apply", "--binary", "--ignore-whitespace", patch.toString());
	}

	/** 추적 중인 파일의 변경 + created 만 커밋한다 (git add -A 를 쓰지 않는다). */
	public boolean commit(Collection<String> created, String subject, String body) {
		return stage(created) && run("git", "commit", "-q", "-m", subject, "-m", body);
	}

	private boolean stage(Collection<String> created) {
		if (!run("git", "add", "-u", "--", ".")) {
			return false;
		}
		List<String> existing = new ArrayList<>();
		for (String file : created) {
			if (Files.exists(this.dir.resolve(file))) {
				existing.add(file);
			}
		}
		if (existing.isEmpty()) {
			return true;
		}
		List<String> add = new ArrayList<>(List.of("git", "add", "--"));
		add.addAll(existing);
		return Processes.run(this.dir, null, add);
	}

	/**
	 * 작업 트리의 현재 상태(추적 중인 파일 + created)를 tree 객체로 만든다. 실제 인덱스는 건드리지 않도록 임시 인덱스를 쓴다. 단계 전후
	 * tree 를 비교하면 그 단계에서만 바뀐 diff 가 나온다.
	 */
	public String snapshotTree(Collection<String> created, Path tempIndex) {
		java.util.Map<String, String> env = java.util.Map.of("GIT_INDEX_FILE", tempIndex.toString());
		try {
			Files.deleteIfExists(tempIndex);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		if (!Processes.run(this.dir, null, List.of("git", "read-tree", "HEAD"), env)
				|| !Processes.run(this.dir, null, List.of("git", "add", "-u", "--", "."), env)) {
			return null;
		}
		List<String> existing = new ArrayList<>();
		for (String file : created) {
			if (Files.exists(this.dir.resolve(file))) {
				existing.add(file);
			}
		}
		if (!existing.isEmpty()) {
			List<String> add = new ArrayList<>(List.of("git", "add", "--"));
			add.addAll(existing);
			Processes.run(this.dir, null, add, env);
		}
		String tree = Processes.capture(this.dir, env, "git", "write-tree");
		return (tree != null) ? tree.trim() : null;
	}

	/** 두 tree 사이의 diff 를 patch 로 남긴다. */
	public boolean diffTrees(String from, String to, Path patch) {
		return Processes.run(this.dir, patch, List.of("git", "diff", "--binary", from, to));
	}

	public String lastCommit() {
		String out = Processes.capture(this.dir, "git", "log", "-1", "--format=%h %s");
		return (out != null) ? out.trim() : "";
	}

	public String recentCommits(int count) {
		String out = Processes.capture(this.dir, "git", "log", "--oneline", "-" + count);
		return (out != null) ? out.trim().replace('\n', ';') : "";
	}

	private boolean run(String... command) {
		return Processes.run(this.dir, null, List.of(command));
	}
}
