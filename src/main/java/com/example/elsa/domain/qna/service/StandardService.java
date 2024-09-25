package com.example.elsa.domain.qna.service;

import com.example.elsa.domain.dataset.entity.DataSet;
import com.example.elsa.domain.dataset.repository.DataSetRepository;
import com.example.elsa.domain.qna.dto.QnaToStandardDto;
import com.example.elsa.domain.qna.dto.StandardDto;
import com.example.elsa.domain.qna.entity.ModelScore;
import com.example.elsa.domain.qna.entity.QnaSet;
import com.example.elsa.domain.qna.entity.Standard;
import com.example.elsa.domain.qna.enums.LLMModel;
import com.example.elsa.domain.qna.repository.ModelScoreRepository;
import com.example.elsa.domain.qna.repository.QnaSetRepository;
import com.example.elsa.domain.qna.repository.StandardRepository;
import com.example.elsa.global.error.CustomException;
import com.example.elsa.global.error.ErrorCode;
import com.example.elsa.global.util.DataFormatting;
import com.example.elsa.global.util.ExcelHelper;
import com.example.elsa.global.util.PythonExecutor;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.example.elsa.global.util.ResponseDto;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StandardService {
    private final StandardRepository standardRepository;
    private final DataSetRepository dataSetRepository;
    private final QnaSetRepository qnaSetRepository;
    private final ModelScoreRepository modelScoreRepository;
    private final AnswerService answerService;
    private final PythonExecutor pythonExecutor;

    @Value("${openai.api.url}")

    public Map<String, Double> analyzeAllStandardQnaSentiments() { //감정 분석
        long startTime = System.currentTimeMillis(); //샐행 시간

        List<Standard> standards = standardRepository.findAll(); //DB에서 모든 표준 객체 가져오기

        Map<String, Double> result = standards.stream().collect(Collectors.toMap( ///standars리스트를 스트림으로 변환
                Standard::getName,
                standard -> {
                    AtomicInteger initialScore = new AtomicInteger(standard.getQnaSetList().size()); //초기 점수와 초기 점수 qna 세트 개수
                    AtomicInteger adjustedScore = new AtomicInteger(initialScore.get()); //조정될 점수 설정

                    List<CompletableFuture<Void>> futures = standard.getQnaSetList().stream() //QnA 세트에 대한 비동기 작업 리스트
                            .map(qnaSet -> pythonExecutor.executeSentimentAnalysis(qnaSet.getAnswer()) //QnA 세트의 답변에 대해 감성 분석을 비동기적으로 실행
                                    .thenAccept(analysisResult -> { //감성 분석 결과를 처리하는 콜백 함수를 정의
                                        if (analysisResult == null || analysisResult.get("average_compound_score") == null || (double) analysisResult.get("average_compound_score") == -2.0) { //유효?
                                            initialScore.decrementAndGet();
                                            adjustedScore.decrementAndGet(); //감소
                                            log.info("Standard {} has a QnA with invalid sentiment. Adjusted score: {}", standard.getName(), adjustedScore.get());
                                        } else {
                                            double averageScore = (double) analysisResult.get("average_compound_score"); //유효 점수가져오기

                                            if (averageScore > 0) {
                                                adjustedScore.decrementAndGet(); //평균 점수가 양수(긍정적)인 경우, 조정 점수를 감소시킵니다.
                                                log.info("Standard {} has negative sentiment for QnA. Adjusted score: {}", standard.getName(), adjustedScore.get());
                                            }

                                            // QnaSet에 감성 분석 결과를 저장
                                            qnaSet.setSentimentScore(averageScore); //점수 계산
                                            qnaSetRepository.save(qnaSet);
                                        }
                                    }))
                            .collect(Collectors.toList());

                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                    double finalScore = (double) adjustedScore.get() / initialScore.get();
                    log.info("Standard {} final score: {}", standard.getName(), finalScore);
                    return finalScore;
                }
        ));

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime; // 실행시간 계산
        log.info("Execution time for analyzeAllStandardQnaSentiments: {} ms", duration);

        return result;
    }


    //여러 질문을 동시에 비동기적으로 처리하여 효율성을 높이고 있습니다. 각 질문에 대해 키워드를 대체하고,
//GPT로부터 답변을 얻은 후, 이를 QnaSet으로 만들어 표준에 추가하는 과정을 병렬로 수행합니다
    /*public CompletableFuture<Void> processQnaAsync(String standardName, List<String> questions, LLMModel model) {
        Standard standard = standardRepository.findByName(standardName)
                .orElseGet(() -> new Standard(standardName));

        List<CompletableFuture<QnaSet>> futureQnaSets = questions.stream()
                .map(question -> {
                    String modifiedQuestion = replaceKeywordsInQuestion(question);
                    return answerService.getAnswer(modifiedQuestion, model)
                            .thenApply(answer -> new QnaSet(modifiedQuestion, answer, model));
                })
                .collect(Collectors.toList());

        CompletableFuture.allOf(futureQnaSets.toArray(new CompletableFuture[0])).join();

        List<QnaSet> qnaSets = futureQnaSets.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        qnaSets.forEach(standard::addQnaSet);
        standardRepository.save(standard);
        return CompletableFuture.completedFuture(null);
    }*/
    public CompletableFuture<Void> processQnaAsync(String standardName, List<String> questions) {
        Standard standard = standardRepository.findByName(standardName)
                .orElseGet(() -> new Standard(standardName));

        List<CompletableFuture<QnaSet>> futureQnaSets = questions.stream()
                .map(question -> {
                    String modifiedQuestion = replaceKeywordsInQuestion(question);
                    return CompletableFuture.completedFuture(new QnaSet(modifiedQuestion, ""));
                })
                .collect(Collectors.toList());

        CompletableFuture.allOf(futureQnaSets.toArray(new CompletableFuture[0])).join();

        List<QnaSet> qnaSets = futureQnaSets.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        qnaSets.forEach(standard::addQnaSet);
        standardRepository.save(standard);
        return CompletableFuture.completedFuture(null);
    }


    //질문 하나 처리 여러 표준 동시처리 하나 질문 응답
    //여러 표준에 동일한 QnA를 추가합니다.
    @Async("taskExecutor")
    public CompletableFuture<Void> addQnaToStandard(QnaToStandardDto qnaToStandardDto, LLMModel model) {
        List<String> standardNameList = qnaToStandardDto.getStandardNameList();
        String question = qnaToStandardDto.getQuestion().trim();

        List<Standard> standards = standardRepository.findByNameIn(standardNameList);
        if (standards.isEmpty()) {
            throw new CustomException(ErrorCode.DATA_NOT_FOUND);
        }

        String modifiedQuestion = replaceKeywordsInQuestion(question);
        CompletableFuture<String> answerFuture = answerService.getAnswer(modifiedQuestion, model);

        return answerFuture.thenAccept(answer -> {
            QnaSet qnaSet = new QnaSet(modifiedQuestion, answer);
            for (Standard standard : standards) {
                standard.addQnaSet(qnaSet);
            }
            standardRepository.saveAll(standards);
        });
    }

    //엑셀 파일에서 QnA 데이터를 파싱하고 처리
    /*public void uploadAndProcessQna(MultipartFile file, LLMModel model) {
        long startTime = System.currentTimeMillis();
        try {
            Map<String, List<String>> data = ExcelHelper.parseQnaFile(file);
            List<CompletableFuture<Void>> futures = data.entrySet().stream()
                    .map(entry -> processQnaAsync(entry.getKey(), entry.getValue(), model))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        } finally {
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            log.info("uploadAndProcessQna 메서드 실행 시간: {} ms", duration);
        }
    }*/
    /////////////////////////////
    public void uploadAndProcessQna(MultipartFile file) {
        long startTime = System.currentTimeMillis();
        try {
            Map<String, List<String>> data = ExcelHelper.parseQnaFile(file);
            for (Map.Entry<String, List<String>> entry : data.entrySet()) {
                String standardName = entry.getKey();
                List<String> questions = entry.getValue();

                Standard standard = standardRepository.findByName(standardName)
                        .orElseGet(() -> new Standard(standardName));

                for (int i = 0; i < questions.size(); i += 2) {
                    String question = questions.get(i);
                    String answer = (i + 1 < questions.size()) ? questions.get(i + 1) : "";
                    QnaSet qnaSet = new QnaSet(question, answer);
                    standard.addQnaSet(qnaSet);
                }

                standardRepository.save(standard);
            }
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        } finally {
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            log.info("uploadAndProcessQna 메서드 실행 시간: {} ms", duration);
        }
    }

    @Transactional
    public void generateAnswersForModel(LLMModel model) {
        List<Standard> standards = standardRepository.findAll();
        for (Standard standard : standards) {
            List<QnaSet> originalQnaSets = new ArrayList<>(standard.getQnaSetList());
            List<QnaSet> newQnaSets = new ArrayList<>();

            for (QnaSet qnaSet : originalQnaSets) {
                if (qnaSet.getModel() == null) {  // 원본 질문만 처리
                    String question = qnaSet.getQuestion();
                    try {
                        String answer = answerService.getAnswer(question, model).get(60, TimeUnit.SECONDS);
                        QnaSet modelAnswer = new QnaSet(question, answer, model);
                        newQnaSets.add(modelAnswer);
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        log.error("Error getting answer for question: {} with model: {}", question, model, e);
                    }
                }
            }

            standard.getQnaSetList().addAll(newQnaSets);
            standardRepository.save(standard);
        }
    }

//    public void addInitialStandards(List<String> standardNames) {
//        for (String name: standardNames) {
//            if (standardRepository.findByName(name).isEmpty()) {
//                createNewStandard(name);
//            }
//        }
//    }

    //새로운 표준을 추가
    public void addStandard(StandardDto standardDto) {

        String standardName = standardDto.getName().trim(); //StandardDto에서 이름을 추출하고 공백을 제거

        if (DataFormatting.isNullOrEmpty(standardName)) {
            throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }

        // 해당 이름의 데이터 셋 있는지 확인
        standardRepository.findByName(standardName)
                // 이미 있으면 에러 발생, 없으면 새로 만들기
                .ifPresentOrElse(
                        dataSet -> {
                            throw new CustomException(ErrorCode.DUPLICATE_DATA);
                        },
                        () -> createNewStandard(standardName) //createNewStandard 호출하여 새 표준 생성

                );
    }

    public List<String> getAllStandardNames() {
        return standardRepository.findAll() //표준 저장소에서 모든 표준을 가져옵니다.
                .stream()
                .map(Standard::getName) //이름만 가져오기
                .collect(Collectors.toList()); //반환
    }

    //특정 표준 이름에 해당하는 모든 QnA 반환
    public List<QnaSet> getAllQnaByStandardName(String standardName) {
        return standardRepository.findByName(standardName) //이름으로 검색
                .map(Standard::getQnaSetList) //표준이 존재하면 해당 표준의 QnA 세트 목록을 반환
                .orElseThrow(() -> new CustomException(ErrorCode.DATA_NOT_FOUND));
    }

    //id로 QnA 세트 제거
    public void removeQnaFromStandard(String standardName, Long qnaSetId) {
        Standard standard = standardRepository.findByName(standardName)
                .orElseThrow(() -> new CustomException(ErrorCode.DATA_NOT_FOUND));
        QnaSet qnaSet = standard.getQnaSetList().stream()
                .filter(q -> q.getId().equals(qnaSetId))//qna 세트목록에서 주어진 id와 일치하는 qna 세트 찾음 스트림을 사용하여 ID가 일치하는 QnA 세트를 필터링
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.DATA_NOT_FOUND));
        standard.removeQnaSet(qnaSet);
        standardRepository.save(standard); //변경된 것 저장
        qnaSetRepository.delete(qnaSet); //QnA 셋에서 해당 QnA 셋 삭제
    }

    //새로운 표준을 생성하고 저장
    private void createNewStandard(String standardName) {
        Standard standard = new Standard(standardName);
        try {
            standardRepository.save(standard);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // question에서 {}로 감싸진 모든 단어들에 대해 랜덤 키워드 변환 작업 수행
    private String replaceKeywordsInQuestion(String question) {
        Matcher matcher = Pattern.compile("\\{(.*?)\\}").matcher(question);
        StringBuffer modifiedQuestion = new StringBuffer();

        while (matcher.find()) {
            String keyword = matcher.group(1).toLowerCase(); // {} 안의 문자를 소문자로 변경
            String replacement = getRandomKeywordForDataSet(keyword); //랜덤 키워드를 가져옵니다.
            log.info("기존: {} -> 변경: {}", keyword, replacement);

            // 매칭된 부분을 대체하여 새로운 문장 생성
            matcher.appendReplacement(modifiedQuestion, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(modifiedQuestion); //마지막 매칭 이후의 나머지 문자열을 결과에 추가

        log.info("변경된 질문: {}", modifiedQuestion);
        return modifiedQuestion.toString();
    }

    // 해당 데이터 셋의 하위 목록중 랜덤으로 키워드 하나 선택
    private String getRandomKeywordForDataSet(String dataSetName) {

        return dataSetRepository.findByName(dataSetName)
                .map(DataSet::getKeywords)//데이터셋이 있으면 데이터 셋의 키워드 목록 가져오기
                .filter(keywords -> !keywords.isEmpty())
                .map(keywords -> keywords.get(new Random().nextInt(keywords.size())))
                .orElse(dataSetName);
    }


    /*public Map<String, Object> calculateScore(String standardName, LLMModel model) {
        Standard standard = standardRepository.findByName(standardName)
                .orElseThrow(() -> new CustomException(ErrorCode.DATA_NOT_FOUND));

        List<QnaSet> qnaSets = standard.getQnaSetList();
        if (qnaSets.isEmpty()) {
            throw new CustomException(ErrorCode.DATA_NOT_FOUND);
        }

        int totalQuestions = qnaSets.size();
        int score = totalQuestions; // 시작 점수를 총 질문 수로 설정

        for (QnaSet qnaSet : qnaSets) {
            String question = qnaSet.getQuestion();
            String excelAnswer = qnaSet.getAnswer().trim().toLowerCase();

            String llmAnswer;
            try {
                llmAnswer = answerService.getAnswer(question, model).get(60, TimeUnit.SECONDS);
                llmAnswer = llmAnswer.trim().toLowerCase();

                // 답변 비교 (부분 일치도 허용)
                if (!llmAnswer.contains(excelAnswer) && !excelAnswer.contains(llmAnswer)) {
                    score--; // 답변이 일치하지 않으면 점수 감소
                    log.info("Incorrect answer for question in {}: Excel: {}, LLM: {}",
                            standardName, excelAnswer, llmAnswer);
                }
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                log.error("Error getting LLM answer for question: {}", question, e);
                score--; // 에러 발생 시에도 점수 감소
            }
        }

        double scoreValue = (double) score / totalQuestions;
        String formattedScore = String.format("%.3f", scoreValue);

        log.info("Standard: {}, Model: {}, Total Questions: {}, Score: {}/{}",
                standardName, model, totalQuestions, score, totalQuestions);

        Map<String, Object> result = new HashMap<>();
        result.put("score", formattedScore);
        result.put("standardName", standardName);
        result.put("model", model.name());
        result.put("totalQuestions", totalQuestions);
        result.put("correctAnswers", score);

        return result;
    }*/
    public Map<String, Object> calculateScore(String standardName, LLMModel model) {
        Standard standard = standardRepository.findByName(standardName)
                .orElseThrow(() -> new CustomException(ErrorCode.DATA_NOT_FOUND));

        List<QnaSet> qnaSets = standard.getQnaSetList();
        if (qnaSets.isEmpty()) {
            throw new CustomException(ErrorCode.DATA_NOT_FOUND);
        }

        int totalQuestions = 0;
        int score = 0;

        for (QnaSet qnaSet : qnaSets) {
            String[] questions = qnaSet.getQuestion().split("\n");
            String[] excelAnswers = qnaSet.getAnswer().split("\n");

            String llmAnswerString;
            try {
                llmAnswerString = answerService.getAnswer(String.join("\n", questions), model).get(60, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                continue;
            }

            String[] llmAnswers = llmAnswerString.split("\n");

            int questionCount = Math.min(excelAnswers.length, llmAnswers.length);
            totalQuestions += questionCount;
            score += questionCount;

            for (int i = 0; i < questionCount; i++) {
                String excelAnswer = extractYesNo(excelAnswers[i]);
                String llmAnswer = extractYesNo(llmAnswers[i]);

                if (!excelAnswer.equalsIgnoreCase(llmAnswer)) {
                    score--;
                }
            }
        }

        double scoreValue = totalQuestions > 0 ? (double) score / totalQuestions : 0.0;
        String formattedScore = String.format("%.3f", scoreValue);

        Map<String, Object> result = new HashMap<>();
        result.put("score", formattedScore);
        result.put("standardName", standardName);
        result.put("model", model.name());
        result.put("totalQuestions", totalQuestions);
        result.put("correctAnswers", score);

        return result;
    }

    private String extractYesNo(String answer) {
        answer = answer.toLowerCase().trim();
        if (answer.contains("yes")) {
            return "yes";
        } else if (answer.contains("no")) {
            return "no";
        }
        return answer;
    }

    @Transactional
    public Map<String, Object> calculateAndSaveScoresForModel(LLMModel model) {
        Map<String, Object> scores = new HashMap<>();
        List<Standard> standards = standardRepository.findAll();

        for (Standard standard : standards) {
            try {
                Map<String, Object> scoreResult = calculateScore(standard.getName(), model);
                double score = Double.parseDouble((String) scoreResult.get("score"));
                ModelScore modelScore = modelScoreRepository.findByStandardNameAndModel(standard.getName(), model)
                        .orElse(new ModelScore(standard, model, score));
                modelScore.setScore(score);
                modelScoreRepository.save(modelScore);

                scores.put(standard.getName(), Map.of(
                        "score", score
                ));
                log.info("Saved score for standard {} and model {}: {}", standard.getName(), model, score);
            } catch (Exception e) {
                log.error("Error calculating and saving score for standard {} and model {}: {}", standard.getName(), model, e.getMessage(), e);
                scores.put(standard.getName(), "Error calculating and saving score");
            }
        }

        return scores;
    }

    @Transactional
    public QnaSet saveScoreToDB(Standard standard, LLMModel model, Map<String, Object> score) {
        QnaSet qnaSet = new QnaSet(standard.getName(), (String) score.get("score"), model);
        qnaSet.setSentimentScore(Double.parseDouble((String) score.get("score")));
        standard.addQnaSet(qnaSet);
        Standard savedStandard = standardRepository.save(standard);
        return savedStandard.getQnaSetList().get(savedStandard.getQnaSetList().size() - 1);
    }

    public Map<LLMModel, Map<String, Object>> getAllScores() {
        Map<LLMModel, Map<String, Object>> allScores = new EnumMap<>(LLMModel.class);
        List<Standard> standards = standardRepository.findAll();

        for (LLMModel model : LLMModel.values()) {
            Map<String, Object> modelScores = new HashMap<>();
            for (Standard standard : standards) {
                ModelScore modelScore = modelScoreRepository.findByStandardNameAndModel(standard.getName(), model)
                        .orElse(new ModelScore(standard, model, 0.0));
                modelScores.put(standard.getName(), Map.of(
                        "score", modelScore.getScore()
                ));
            }
            allScores.put(model, modelScores);
        }

        return allScores;
    }
}
