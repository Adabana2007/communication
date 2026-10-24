/* 固定/共享摄像头 WHEP 播放器。权威源在 hybrid/html；后台与鸿蒙由同步脚本生成副本。 */
(function (root) {
  'use strict'
  function create(video, options) {
    const config = Object.assign({ requestMs: 8000, firstFrameMs: 15000, budgetMs: 30000, stallMs: 10000, maxRetries: 2 }, options)
    let active = false, generation = 0, attempt = null, cycle = null, retryTimer = null, budgetTimer = null
    let stableSince = 0, recovering = false
    const now = () => Date.now()
    const emit = (state, message, detail) => { if (active && config.onState) config.onState(state, message, detail || {}) }
    const valid = a => active && a === attempt && !a.closed
    function deleteSession(url) {
      if (!url) return
      const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 2000)
      fetch(url, { method: 'DELETE', signal: controller.signal })
        .catch(error => console.debug('[CameraPlayer] 清理 WHEP 会话失败:', error))
        .finally(() => clearTimeout(timer))
    }
    function dispose(a) {
      if (!a || a.closed) return
      a.closed = true
      a.controller.abort()
      for (const timer of a.timers) clearTimeout(timer)
      a.timers.clear()
      if (a.frameId != null && video.cancelVideoFrameCallback) video.cancelVideoFrameCallback(a.frameId)
      video.removeEventListener('loadeddata', a.loaded)
      a.pc.ontrack = a.pc.onconnectionstatechange = a.pc.onicegatheringstatechange = null
      try { a.pc.close() } catch (error) { console.debug('[CameraPlayer] 关闭 PeerConnection 失败:', error) }
      deleteSession(a.session)
      if (video.srcObject === a.stream) {
        a.stream.getTracks().forEach(t => t.stop())
        video.srcObject = null
      }
    }
    function endCycle() { clearTimeout(budgetTimer); budgetTimer = null; cycle = null; recovering = false }
    function beginCycle(isRecovery) {
      cycle = { deadline: now() + config.budgetMs, retries: 0 }
      recovering = isRecovery
      stableSince = 0
      clearTimeout(budgetTimer)
      budgetTimer = setTimeout(() => fail('播放恢复超时，请重试或执行摄像头诊断', { code: 'BUDGET' }), config.budgetMs)
    }
    function fail(message, detail) {
      if (!active) return
      clearTimeout(retryTimer); clearTimeout(budgetTimer)
      dispose(attempt); attempt = null
      emit('error', message, detail)
      active = false
    }
    function retry(a, message, detail, retryable = true, delayMs = 1000) {
      if (!valid(a)) return
      dispose(a); attempt = null
      if (!cycle) beginCycle(true)
      stableSince = 0
      if (!retryable || cycle.retries >= config.maxRetries || now() >= cycle.deadline) { fail(message, detail); return }
      cycle.retries++
      emit('connecting', message + '，正在重试 ' + cycle.retries + '/' + config.maxRetries, detail)
      const g = generation
      retryTimer = setTimeout(() => { if (active && generation === g) connect() }, delayMs)
    }
    function frame(a) {
      if (!valid(a)) return
      a.lastProgress = now()
      if (!a.playing) {
        a.playing = true; stableSince = now()
        clearTimeout(a.firstTimer)
        if (!recovering) endCycle()
        emit('playing', '播放正常', { code: 'FRAME', videoBytes: a.bytes, framesDecoded: a.frames })
        if (config.onReady) config.onReady(a.pc)
      }
    }
    function watchFrame(a) {
      if (!valid(a) || !video.requestVideoFrameCallback || a.watching) return
      a.watching = true
      a.frameId = video.requestVideoFrameCallback(() => { a.watching = false; frame(a); watchFrame(a) })
    }
    function later(a, fn, ms) {
      const id = setTimeout(() => { a.timers.delete(id); if (valid(a)) fn() }, ms)
      a.timers.add(id); return id
    }
    async function monitor(a) {
      if (!valid(a)) return
      try {
        const stats = await a.pc.getStats()
        if (!valid(a)) return
        let bytes = 0, frames = 0
        stats.forEach(s => { if (s.type === 'inbound-rtp' && (s.kind === 'video' || s.mediaType === 'video') && !s.isRemote) {
          bytes += Number(s.bytesReceived || 0); frames += Number(s.framesDecoded || 0)
        } })
        const advanced = frames > a.frames
        if (advanced) a.lastProgress = now()
        a.bytes = bytes; a.frames = frames
        // 旧 WebView 无 rVFC 时使用真实 decoded 帧 + video 尺寸/就绪态。
        if (!video.requestVideoFrameCallback && advanced && video.videoWidth > 0 && video.readyState >= 2) frame(a)
        if (config.onDiagnostic) config.onDiagnostic({ code: 'CLIENT', ice: a.pc.iceConnectionState || a.pc.connectionState,
          videoBytes: bytes, framesDecoded: frames, rendered: a.playing })
      } catch (error) {
        // 老内核 getStats 不支持时仍由视频帧回调/loadeddata 判断。
        console.debug('[CameraPlayer] getStats 不可用:', error)
      }
      if (!valid(a)) return
      if (a.playing && now() - a.lastProgress >= config.stallMs) {
        retry(a, '视频帧停止更新', { code: 'STALLED', videoBytes: a.bytes, framesDecoded: a.frames }); return
      }
      if (recovering && a.playing && stableSince && now() - stableSince >= 10000) endCycle()
      later(a, () => monitor(a), 1000)
    }
    function waitIce(a) {
      if (a.pc.iceGatheringState === 'complete') return Promise.resolve()
      return new Promise((resolve, reject) => {
        const timer = later(a, () => reject(new Error('ICE_GATHER_TIMEOUT')), 4000)
        a.pc.onicegatheringstatechange = () => {
          if (valid(a) && a.pc.iceGatheringState === 'complete') { clearTimeout(timer); resolve() }
        }
        a.controller.signal.addEventListener('abort', () => reject(new Error('CANCELLED')), { once: true })
      })
    }
    async function connect() {
      if (!active) return
      const a = { pc: new RTCPeerConnection({ iceServers: config.iceServers || [] }), controller: new AbortController(),
        timers: new Set(), closed: false, stream: new MediaStream(), session: null, bytes: 0, frames: 0, playing: false,
        lastProgress: now(), frameId: null, loaded: null }
      attempt = a
      emit('connecting', '正在连接视频流', { code: 'CONNECTING' })
      a.loaded = () => {
        if (!video.requestVideoFrameCallback && video.videoWidth > 0 && video.readyState >= 2) frame(a)
      }
      video.addEventListener('loadeddata', a.loaded)
      a.pc.ontrack = event => {
        if (!valid(a)) { event.track.stop(); return }
        if (!a.stream.getTracks().includes(event.track)) a.stream.addTrack(event.track)
        video.srcObject = a.stream
        if (config.onTrack) config.onTrack(event)
        if (config.play) config.play()
        else {
          const p = video.play()
          if (p && p.catch) p.catch(error => console.debug('[CameraPlayer] 自动播放被浏览器拦截:', error))
        }
        watchFrame(a)
      }
      a.pc.onconnectionstatechange = () => {
        if (!valid(a)) return
        if (a.pc.connectionState === 'failed' || a.pc.connectionState === 'disconnected')
          retry(a, '媒体连接中断', { code: 'ICE', ice: a.pc.iceConnectionState || a.pc.connectionState })
        else if (a.pc.connectionState === 'connected' && !a.playing) emit('connecting', '连接已建立，等待首帧', { code: 'WAIT_FRAME' })
      }
      // 整个 attempt 都受首帧期限约束，包括 createOffer/setRemoteDescription。
      a.firstTimer = later(a, () => retry(a,
        a.bytes > 0 ? (a.frames > 0 ? '视频已解码但尚未渲染，请检查播放策略' : '已收到视频数据但未解码出画，请检查编码兼容性或关键帧') : '未收到视频媒体，请检查源就绪状态及 ICE 网络',
        { code: a.bytes > 0 ? (a.frames > 0 ? 'RENDER' : 'DECODE') : 'NO_MEDIA', videoBytes: a.bytes, framesDecoded: a.frames }), config.firstFrameMs)
      monitor(a)
      try {
        await a.pc.setLocalDescription(await a.pc.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: true }))
        if (!valid(a)) return
        await waitIce(a)
        if (!valid(a)) return
        const url = config.url.replace(/\/+$/, '').replace(/\/whep$/, '') + '/whep'
        const timeout = later(a, () => a.controller.abort(), config.requestMs)
        const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/sdp' },
          body: a.pc.localDescription.sdp, signal: a.controller.signal })
        const session = response.headers.get('Location')
        if (session) {
          const resolved = new URL(session, url)
          if (resolved.origin === new URL(url).origin) a.session = resolved.href
        }
        if (!valid(a)) { deleteSession(a.session); return }
        if (!response.ok) {
          const nonretryable = [400, 401, 403, 405, 406, 415, 422].includes(response.status)
          const message = [401, 403].includes(response.status) ? '播放接口认证失败或无权限' :
            [400, 406, 415, 422].includes(response.status) ? '媒体协商失败，请执行诊断确认编码和路径' : '播放接口返回 HTTP ' + response.status
          retry(a, message, { code: 'HTTP', status: response.status }, !nonretryable, response.status === 404 || response.status === 503 ? 5000 : 1000); return
        }
        const answer = await response.text()
        clearTimeout(timeout)
        if (!valid(a)) return
        await a.pc.setRemoteDescription({ type: 'answer', sdp: answer })
      } catch (error) {
        if (!valid(a)) return
        retry(a, error.name === 'AbortError' ? '播放请求超时' : '视频连接或媒体协商失败', { code: 'REQUEST' }, error.name !== 'OperationError')
      }
    }
    function stop() {
      active = false; generation++
      clearTimeout(retryTimer); clearTimeout(budgetTimer)
      dispose(attempt); attempt = null; cycle = null
    }
    function start() {
      stop(); active = true
      if (!config.url || typeof RTCPeerConnection === 'undefined' || typeof AbortController === 'undefined' || typeof MediaStream === 'undefined') { fail('当前环境不支持 WebRTC 或播放地址为空', { code: 'UNSUPPORTED' }); return }
      beginCycle(false); connect()
    }
    return { start, stop }
  }
  root.CameraPlayer = { create }
})(typeof globalThis === 'undefined' ? window : globalThis)
