package com.github.ioloolo.onlinejudge.domain.judge.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;

import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.ioloolo.onlinejudge.config.security.services.UserDetailsImpl;
import com.github.ioloolo.onlinejudge.domain.auth.model.User;
import com.github.ioloolo.onlinejudge.domain.auth.repository.UserRepository;
import com.github.ioloolo.onlinejudge.domain.judge.model.History;
import com.github.ioloolo.onlinejudge.domain.judge.model.Judge;
import com.github.ioloolo.onlinejudge.domain.judge.repository.JudgeRepository;
import com.github.ioloolo.onlinejudge.domain.problem.context.request.JudgeRequest;
import com.github.ioloolo.onlinejudge.domain.problem.exception.ProblemNotExistException;
import com.github.ioloolo.onlinejudge.domain.problem.model.Problem;
import com.github.ioloolo.onlinejudge.domain.problem.repository.ProblemRepository;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class JudgeService {

	private final UserRepository userRepository;
	private final ProblemRepository problemRepository;
	private final JudgeRepository judgeRepository;

	private final DockerClient dockerClient = DockerClientBuilder.getInstance().build();

	public void pushJudgeQueue(UserDetailsImpl userImpl, JudgeRequest request) throws ProblemNotExistException {
		User user = userRepository.findById(new ObjectId(userImpl.getId()))
				.orElseThrow();

		Problem problem = problemRepository.findByProblemId(request.getProblemId())
				.orElseThrow(() -> new ProblemNotExistException(request.getProblemId()));

		Judge judge = Judge.builder()
				.user(user)
				.problem(problem)
				.language(request.getLanguage())
				.source(request.getSource())
				.at(ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toLocalDateTime())
				.result(Judge.Result.builder().type(Judge.Result.Type.WAITING).build())
				.build();

		Judge judge1 = judgeRepository.save(judge);

		Executors.newSingleThreadExecutor().submit(() -> {
			CreateContainerResponse exec = dockerClient.createContainerCmd("online-judge-ioloolo")
					.withName("judge-%s".formatted(judge1.getId().toString()))
					.withEnv("JUDGE_ID=%s".formatted(judge1.getId().toString()))
					.exec();

			dockerClient.startContainerCmd(exec.getId()).exec();

			dockerClient.waitContainerCmd(exec.getId())
					.exec(new WaitContainerResultCallback())
					.awaitStatusCode();

			dockerClient.removeContainerCmd(exec.getId())
					.exec();
		});
	}

	public List<History> getHistory(UserDetailsImpl user) {
		return judgeRepository.findAll()
				.stream()
				.map(judge -> History.from(judge, user))
				.sorted(Comparator.comparing(History::getAt))
				.limit(25)
				.toList();
	}
}
