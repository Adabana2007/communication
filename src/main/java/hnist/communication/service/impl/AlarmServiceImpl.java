package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.Alarm;
import hnist.communication.entity.AlarmMedia;
import hnist.communication.entity.User;
import hnist.communication.mapper.AlarmMapper;
import hnist.communication.mapper.AlarmMediaMapper;
import hnist.communication.mapper.UserMapper;
import hnist.communication.service.AlarmService;
import hnist.communication.util.MediaFileValidator;
import hnist.communication.vo.AlarmVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 报警记录的创建、分页查询与处理状态流转。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmServiceImpl implements AlarmService {

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_DONE = "done";
    private static final Set<String> STATUSES = Set.of(STATUS_PENDING, STATUS_PROCESSING, STATUS_DONE);
    private static final Set<String> IMAGE_EXTS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Set<String> VOICE_EXTS = Set.of("mp3", "m4a", "amr", "wav", "aac");
    private static final long MAX_ALARM_MEDIA_BYTES = 16L * 1024 * 1024;
    private static final int MAGIC_HEADER_BYTES = 32;
    private static final int MAX_FILE_NAME_LENGTH = 120;
    private static final int MAX_ALARM_MEDIA_COUNT = 20;

    private final AlarmMapper alarmMapper;
    private final UserMapper userMapper;
    private final AlarmMediaMapper alarmMediaMapper;

    @Override
    @Transactional
    public Alarm create(Alarm alarm) {
        // 新报警一律从未处理开始，处理人/处理时间/备注只能由后台状态流转写入。
        alarm.setId(null);
        alarm.setStatus(STATUS_PENDING);
        alarm.setHandledBy(null);
        alarm.setHandledAt(null);
        alarm.setHandleRemark(null);
        alarm.setCreatedAt(LocalDateTime.now());
        alarmMapper.insert(alarm);
        return alarm;
    }

    @Override
    @Transactional
    public Alarm createWithMedia(Alarm alarm, List<Long> imageMediaIds, Long voiceMediaId) {
        Alarm saved = create(alarm);
        Set<Long> imageIds = new LinkedHashSet<>();
        if (imageMediaIds != null) {
            imageMediaIds.stream().filter(Objects::nonNull).forEach(imageIds::add);
        }
        if (imageIds.size() + (voiceMediaId == null ? 0 : 1) > MAX_ALARM_MEDIA_COUNT) {
            throw new BusinessException("报警媒体数量超过限制");
        }
        if (!imageIds.isEmpty()) {
            int boundImages = alarmMediaMapper.update(null, new LambdaUpdateWrapper<AlarmMedia>()
                    .set(AlarmMedia::getAlarmId, saved.getId())
                    .in(AlarmMedia::getId, imageIds)
                    .eq(AlarmMedia::getUploaderId, saved.getUserId())
                    .eq(AlarmMedia::getMediaType, "image")
                    .isNull(AlarmMedia::getAlarmId));
            if (boundImages != imageIds.size()) {
                throw new BusinessException("报警图片无效、无权使用或已被其他报警占用");
            }
        }
        if (voiceMediaId != null) {
            // 仅绑定本人上传、类型匹配且尚未被认领的媒体，避免猜测 ID 劫持他人文件。
            int boundVoice = alarmMediaMapper.update(null, new LambdaUpdateWrapper<AlarmMedia>()
                    .set(AlarmMedia::getAlarmId, saved.getId())
                    .eq(AlarmMedia::getId, voiceMediaId)
                    .eq(AlarmMedia::getUploaderId, saved.getUserId())
                    .eq(AlarmMedia::getMediaType, "voice")
                    .isNull(AlarmMedia::getAlarmId));
            if (boundVoice != 1) {
                throw new BusinessException("报警语音无效、无权使用或已被其他报警占用");
            }
        }
        return saved;
    }

    @Override
    @Transactional
    public AlarmMedia storeMedia(MultipartFile file, Long uploaderId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件为空");
        }
        if (file.getSize() > MAX_ALARM_MEDIA_BYTES) {
            throw new BusinessException("报警媒体不能超过16MB");
        }
        String originalName = safeFileName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        String mediaType;
        if (IMAGE_EXTS.contains(extension)) {
            mediaType = "image";
        } else if (VOICE_EXTS.contains(extension)) {
            mediaType = "voice";
        } else {
            throw new BusinessException("不支持的文件类型");
        }
        try {
            byte[] header;
            try (InputStream input = file.getInputStream()) {
                header = input.readNBytes(MAGIC_HEADER_BYTES);
            }
            if (!MediaFileValidator.matches(extension, header)) {
                throw new BusinessException("文件内容与扩展名不匹配");
            }
            // 上限已在前面校验，这里按上限+1 读取，避免大文件被无限读入内存。
            byte[] data;
            try (InputStream input = file.getInputStream()) {
                data = input.readNBytes((int) MAX_ALARM_MEDIA_BYTES + 1);
            }
            if (data.length > MAX_ALARM_MEDIA_BYTES) {
                throw new BusinessException("报警媒体不能超过16MB");
            }
            AlarmMedia media = AlarmMedia.builder()
                    .uploaderId(uploaderId)
                    .mediaType(mediaType)
                    .mime(mimeOf(extension))
                    .fileName(originalName)
                    .data(data)
                    .size((long) data.length)
                    .createdAt(LocalDateTime.now())
                    .build();
            alarmMediaMapper.insert(media);
            return media;
        } catch (IOException e) {
            log.error("保存报警媒体失败: uploaderId={}, fileName={}", uploaderId, originalName, e);
            throw new BusinessException("文件保存失败");
        }
    }

    @Override
    public AlarmMedia getMedia(Long id) {
        return alarmMediaMapper.selectById(id);
    }

    @Override
    public Page<AlarmVO> list(int page, int size, Long userId, String keyword,
                              String status, String start, String end) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<Alarm> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(Alarm::getUserId, userId);
        }
        // 报警人关键词：先查 user_id 集合（用户名/昵称 like）
        if (keyword != null && !keyword.isBlank()) {
            List<Long> matched = userMapper.selectList(new LambdaQueryWrapper<User>()
                            .like(User::getUsername, keyword.trim()).or()
                            .like(User::getNickname, keyword.trim()))
                    .stream().map(User::getUserId).toList();
            if (matched.isEmpty()) {
                return new Page<>(safePage, safeSize);
            }
            wrapper.in(Alarm::getUserId, matched);
        }
        if (status != null && !status.isBlank() && STATUSES.contains(status)) {
            wrapper.eq(Alarm::getStatus, status);
        }
        if (start != null && !start.isBlank()) {
            wrapper.ge(Alarm::getCreatedAt, parseDate(start, "开始日期").atStartOfDay());
        }
        if (end != null && !end.isBlank()) {
            wrapper.le(Alarm::getCreatedAt, parseDate(end, "结束日期").atTime(23, 59, 59));
        }
        wrapper.orderByDesc(Alarm::getCreatedAt);

        Page<Alarm> result = alarmMapper.selectPage(new Page<>(safePage, safeSize), wrapper);

        // 批量查用户名/昵称与处理人，避免 N+1
        Set<Long> userIds = result.getRecords().stream()
                .map(Alarm::getUserId).collect(Collectors.toSet());
        Set<Long> handledIds = result.getRecords().stream()
                .map(Alarm::getHandledBy).filter(id -> id != null).collect(Collectors.toSet());
        userIds.addAll(handledIds);
        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap()
                : userMapper.selectByIds(new ArrayList<>(userIds)).stream()
                        .collect(Collectors.toMap(User::getUserId, u -> u, (left, right) -> left));

        Page<AlarmVO> alarmPage = (Page<AlarmVO>) result.convert(a -> {
            User u = userMap.get(a.getUserId());
            User h = a.getHandledBy() == null ? null : userMap.get(a.getHandledBy());
            return AlarmVO.from(a,
                    u != null ? u.getUsername() : null,
                    u != null ? u.getNickname() : null,
                    h != null ? (h.getNickname() != null ? h.getNickname() : h.getUsername()) : null);
        });
        // 媒体单独存放在 alarm_media：按 alarm_id 批量回填图片与语音 id，避免逐条查询。
        if (!alarmPage.getRecords().isEmpty()) {
            List<Long> alarmIds = alarmPage.getRecords().stream().map(AlarmVO::getId).collect(Collectors.toList());
            List<AlarmMedia> medias = alarmMediaMapper.selectList(new LambdaQueryWrapper<AlarmMedia>()
                    .in(AlarmMedia::getAlarmId, alarmIds));
            Map<Long, List<Long>> mediaMap = medias.stream()
                    .collect(Collectors.groupingBy(AlarmMedia::getAlarmId,
                            Collectors.mapping(AlarmMedia::getId, Collectors.toList())));
            Map<Long, List<Long>> voiceMap = new java.util.HashMap<>();
            for (AlarmMedia m : medias) {
                if ("voice".equals(m.getMediaType())) {
                    voiceMap.computeIfAbsent(m.getAlarmId(), k -> new ArrayList<>()).add(m.getId());
                }
            }
            for (AlarmVO vo : alarmPage.getRecords()) {
                vo.setImageMediaIds(mediaMap.getOrDefault(vo.getId(), Collections.emptyList()));
                List<Long> vs = voiceMap.getOrDefault(vo.getId(), Collections.emptyList());
                // 接口只暴露单个 voiceMediaId，语音固定取该报警下的第一条。
                vo.setVoiceMediaId(vs.isEmpty() ? null : vs.get(0));
            }
        }
        return alarmPage;
    }

    /** 状态仅允许 pending/processing/done；done 时记录处理人与处理时间。 */
    @Override
    @Transactional
    public void updateStatus(Long id, String status, String remark, Long operatorId) {
        if (status == null || !STATUSES.contains(status)) {
            throw new BusinessException("状态值不合法");
        }
        Alarm alarm = alarmMapper.selectById(id);
        if (alarm == null) {
            throw new BusinessException("报警记录不存在");
        }
        alarm.setStatus(status);
        if (remark != null && !remark.isBlank()) {
            alarm.setHandleRemark(remark);
        }
        if (STATUS_DONE.equals(status)) {
            alarm.setHandledBy(operatorId);
            alarm.setHandledAt(LocalDateTime.now());
        } else {
            alarm.setHandledBy(null);
            alarm.setHandledAt(null);
        }
        alarmMapper.updateById(alarm);
    }

    private static String safeFileName(String original) {
        String value = original == null ? "" : original.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1);
        if (value.length() > MAX_FILE_NAME_LENGTH) {
            value = value.substring(value.length() - MAX_FILE_NAME_LENGTH);
        }
        return value;
    }

    private static String extensionOf(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private static java.time.LocalDate parseDate(String value, String fieldName) {
        try {
            return java.time.LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException(fieldName + "格式必须为yyyy-MM-dd");
        }
    }

    private static String mimeOf(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "wav" -> "audio/wav";
            case "amr" -> "audio/amr";
            case "aac" -> "audio/aac";
            default -> "application/octet-stream";
        };
    }
}
