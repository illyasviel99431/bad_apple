import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Main extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final String APP_TITLE = "Bad Apple Player";
    private static final String DATA_FILE_NAME = "bad_apple.txt";
    private static final String FRAME_DELIMITER = "SPLIT";
    private static final int DEFAULT_FPS = 10;
    private static final int MIN_FPS = 1;
    private static final int MAX_FPS = 60;

    private final JTextArea frameArea = new JTextArea(30, 100);
    private final JLabel statusLabel = new JLabel();
    private final JButton playPauseButton = new JButton();
    private final JSlider fpsSlider;

    private transient Path dataFile;
    private transient BufferedReader reader;
    private transient Timer timer;
    private transient final List<String> currentFrame = new ArrayList<>();
    private transient List<Integer> expectedLineWidths;
    private int expectedLineCount = -1;
    private int fps = DEFAULT_FPS;
    private long displayedFrames;
    private long completedFrames;
    private boolean readerFinished;
    private boolean playing;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> launch(args));
    }

    private static void launch(String[] args) {
        try {
            Main player = new Main(args);
            player.setVisible(true);
        } catch (IllegalArgumentException | IOException e) {
            showError(e.getMessage());
        }
    }

    private Main(String[] args) throws IOException {
        super(APP_TITLE);

        Path explicitFile = parseArguments(args);
        dataFile = findDataFile(explicitFile);
        if (dataFile == null) {
            String message = explicitFile == null
                    ? DATA_FILE_NAME + " 파일을 찾을 수 없습니다.\n"
                    + "프로젝트의 src 폴더 또는 실행 파일 옆에 파일을 두세요."
                    : "지정한 프레임 파일을 읽을 수 없습니다.\n" + explicitFile.toAbsolutePath();
            throw new IOException(message);
        }

        configureFrameArea();
        fpsSlider = createFpsSlider();
        timer = new Timer(delayFor(fps), event -> showNextFrame());
        timer.setCoalesce(true);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(new JScrollPane(frameArea), BorderLayout.CENTER);
        add(createControls(), BorderLayout.SOUTH);
        getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setMinimumSize(new Dimension(700, 420));
        pack();
        setLocationByPlatform(true);

        restartPlayback();
    }

    private Path parseArguments(String[] args) {
        Path explicitFile = null;

        for (String arg : args) {
            if (arg.startsWith("--fps=")) {
                String value = arg.substring("--fps=".length());
                try {
                    fps = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("FPS 값이 올바르지 않습니다: " + value);
                }
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("알 수 없는 옵션입니다: " + arg);
            } else if (explicitFile == null) {
                explicitFile = Paths.get(arg);
            } else {
                throw new IllegalArgumentException("프레임 파일은 하나만 지정할 수 있습니다.");
            }
        }

        if (fps < MIN_FPS || fps > MAX_FPS) {
            throw new IllegalArgumentException("FPS는 " + MIN_FPS + "에서 " + MAX_FPS + " 사이여야 합니다.");
        }
        return explicitFile;
    }

    private static Path findDataFile(Path explicitFile) {
        if (explicitFile != null) {
            Path file = explicitFile.toAbsolutePath().normalize();
            return isReadableFile(file) ? file : null;
        }

        Set<Path> searchRoots = new LinkedHashSet<>();
        searchRoots.add(Paths.get("").toAbsolutePath().normalize());

        try {
            Path codeLocation = Paths.get(Main.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path codeDirectory = Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
            if (codeDirectory != null) {
                searchRoots.add(codeDirectory);
            }
        } catch (URISyntaxException | SecurityException ignored) {
            // 현재 작업 폴더를 기준으로 한 검색은 계속 시도한다.
        }

        for (Path root : searchRoots) {
            for (Path directory = root; directory != null; directory = directory.getParent()) {
                Path directFile = directory.resolve(DATA_FILE_NAME);
                if (isReadableFile(directFile)) {
                    return directFile;
                }

                Path sourceFile = directory.resolve("src").resolve(DATA_FILE_NAME);
                if (isReadableFile(sourceFile)) {
                    return sourceFile;
                }
            }
        }
        return null;
    }

    private static boolean isReadableFile(Path file) {
        return Files.isRegularFile(file) && Files.isReadable(file);
    }

    private static void showError(String message) {
        JOptionPane.showMessageDialog(null, message, APP_TITLE, JOptionPane.ERROR_MESSAGE);
    }

    private static int delayFor(int fps) {
        return Math.max(1, Math.round(1000.0f / fps));
    }

    private void configureFrameArea() {
        frameArea.setEditable(false);
        frameArea.setFocusable(false);
        frameArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        frameArea.setBackground(Color.BLACK);
        frameArea.setForeground(Color.WHITE);
        frameArea.setCaretColor(Color.WHITE);
        frameArea.setMargin(new Insets(8, 8, 8, 8));
        frameArea.setLineWrap(false);
    }

    private JPanel createControls() {
        JPanel controls = new JPanel(new BorderLayout(8, 0));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        playPauseButton.addActionListener(event -> togglePlayback());
        JButton restartButton = new JButton("처음부터");
        restartButton.addActionListener(event -> restartPlayback());

        buttons.add(playPauseButton);
        buttons.add(restartButton);
        buttons.add(new JLabel("속도"));
        buttons.add(fpsSlider);

        controls.add(buttons, BorderLayout.WEST);
        controls.add(statusLabel, BorderLayout.EAST);
        return controls;
    }

    private JSlider createFpsSlider() {
        JSlider slider = new JSlider(MIN_FPS, MAX_FPS, fps);
        slider.setMajorTickSpacing(10);
        slider.setMinorTickSpacing(1);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        slider.addChangeListener(event -> {
            fps = slider.getValue();
            if (timer != null) {
                timer.setDelay(delayFor(fps));
            }
            updateStatus();
        });
        return slider;
    }

    private void togglePlayback() {
        if (playing) {
            timer.stop();
            playing = false;
            playPauseButton.setText("계속 재생");
            updateStatus();
        } else if (reader != null) {
            startPlayback();
        }
    }

    private void restartPlayback() {
        timer.stop();
        closeReader();
        try {
            resetReader();
            displayedFrames = 0;
            frameArea.setText("");
            playPauseButton.setEnabled(true);
            startPlayback();
        } catch (IOException e) {
            playing = false;
            playPauseButton.setEnabled(false);
            statusLabel.setText("파일을 열 수 없습니다.");
            showError("프레임 파일을 열 수 없습니다.\n" + e.getMessage());
        }
    }

    private void resetReader() throws IOException {
        reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8);
        currentFrame.clear();
        expectedLineWidths = null;
        expectedLineCount = -1;
        completedFrames = 0;
        readerFinished = false;
    }

    private void startPlayback() {
        if (reader == null) {
            return;
        }
        playing = true;
        playPauseButton.setText("일시 정지");
        timer.setDelay(delayFor(fps));
        timer.setInitialDelay(0);
        timer.start();
        updateStatus();
    }

    private void showNextFrame() {
        try {
            String frame = nextFrame();
            if (frame == null) {
                finishPlayback();
                return;
            }

            frameArea.setText(frame);
            frameArea.setCaretPosition(0);
            displayedFrames++;
            updateStatus();
        } catch (IOException e) {
            timer.stop();
            playing = false;
            closeReader();
            playPauseButton.setEnabled(false);
            statusLabel.setText("재생 중 오류가 발생했습니다.");
            showError("프레임 데이터를 읽는 중 오류가 발생했습니다.\n" + e.getMessage());
        }
    }

    private String nextFrame() throws IOException {
        if (readerFinished) {
            return null;
        }

        String line;
        while ((line = reader.readLine()) != null) {
            int delimiterIndex = indexOfDelimiter(line);
            if (delimiterIndex < 0) {
                currentFrame.add(line);
                continue;
            }

            // SPLIT 앞은 현재 프레임의 마지막 행, 뒤는 다음 프레임의 첫 행이다.
            String beforeDelimiter = line.substring(0, delimiterIndex);
            if (!beforeDelimiter.isEmpty()) {
                currentFrame.add(beforeDelimiter);
            }

            String completeFrame = completeCurrentFrame();

            String afterDelimiter = line.substring(delimiterIndex + FRAME_DELIMITER.length());
            if (!afterDelimiter.isEmpty()) {
                currentFrame.add(afterDelimiter);
            }

            if (completeFrame != null) {
                return completeFrame;
            }
        }

        readerFinished = true;
        return completeCurrentFrame();
    }

    private String completeCurrentFrame() throws IOException {
        if (currentFrame.isEmpty()) {
            return null;
        }

        validateFrameShape();
        completedFrames++;
        String frame = String.join("\n", currentFrame);
        currentFrame.clear();
        return frame;
    }

    private void validateFrameShape() throws IOException {
        if (expectedLineCount < 0) {
            expectedLineCount = currentFrame.size();
            expectedLineWidths = new ArrayList<>(expectedLineCount);
            for (String line : currentFrame) {
                expectedLineWidths.add(line.length());
            }
            return;
        }

        if (currentFrame.size() != expectedLineCount) {
            throw new IOException("프레임 " + (completedFrames + 1) + "의 행 수가 일치하지 않습니다.");
        }
        for (int index = 0; index < currentFrame.size(); index++) {
            if (currentFrame.get(index).length() != expectedLineWidths.get(index)) {
                throw new IOException("프레임 " + (completedFrames + 1) + "의 "
                        + (index + 1) + "번째 행 너비가 일치하지 않습니다.");
            }
        }
    }

    private void finishPlayback() {
        timer.stop();
        playing = false;
        closeReader();
        playPauseButton.setText("재생 완료");
        playPauseButton.setEnabled(false);
        statusLabel.setText("재생 완료 · " + displayedFrames + " 프레임");
    }

    private void updateStatus() {
        String state = playing ? "재생 중" : "일시 정지";
        statusLabel.setText(state + " · " + displayedFrames + " 프레임 · " + fps + " FPS");
    }

    private void closeReader() {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (IOException ignored) {
            // 창을 닫는 과정에서는 별도 알림이 필요하지 않다.
        } finally {
            reader = null;
        }
    }

    @Override
    public void dispose() {
        if (timer != null) {
            timer.stop();
        }
        closeReader();
        super.dispose();
    }

    private static int indexOfDelimiter(String line) {
        int lastStart = line.length() - FRAME_DELIMITER.length();
        for (int index = 0; index <= lastStart; index++) {
            if (line.regionMatches(true, index, FRAME_DELIMITER, 0, FRAME_DELIMITER.length())) {
                return index;
            }
        }
        return -1;
    }
}
