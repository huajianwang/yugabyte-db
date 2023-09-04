// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#include "yb/util/wait_state.h"

#include <arpa/inet.h>
#include <unordered_map>

#include "yb/util/debug-util.h"
#include "yb/util/tostring.h"
#include "yb/util/thread.h"
#include "yb/util/trace.h"

using yb::util::WaitStateCode;

DEFINE_RUNTIME_bool(freeze_wait_states, true, "Fetches the frozen wait state, as of the time when "
                    "GetAUHMetadata was called, instead of getting a jagged-edge across different rpcs.");
namespace yb::util {

thread_local WaitStateInfoPtr WaitStateInfo::threadlocal_wait_state_;
std::atomic<bool> WaitStateInfo::freeze_{false};

void AUHMetadata::set_client_node_ip(const std::string &endpoint) {
  client_node_host = 0;
  client_node_port = 0;

  size_t colon_position = endpoint.find(':');
  client_node_host = ntohl(inet_addr(endpoint.substr(0, colon_position).c_str()));
  client_node_port = std::stoi(endpoint.substr(colon_position + 1));
}

std::string AUHAuxInfo::ToString() const {
  return YB_STRUCT_TO_STRING(table_id, tablet_id, method);
}

void AUHAuxInfo::UpdateFrom(const AUHAuxInfo &other) {
  if (!other.tablet_id.empty()) {
    tablet_id = other.tablet_id;
  }
  if (!other.table_id.empty()) {
    table_id = other.table_id;
  }
  if (!other.method.empty()) {
    method = other.method;
  }
}

WaitStateInfo::WaitStateInfo(AUHMetadata meta)
  : metadata_(meta)
#ifndef NDEBUG
  , thread_id_(0)
#endif
#ifdef TRACK_WAIT_HISTORY
  , num_updates_(0) 
#endif 
  {}

simple_spinlock* WaitStateInfo::get_mutex() {
  return &mutex_;
};

void WaitStateInfo::freeze() {
  freeze_ = true;
}

void WaitStateInfo::unfreeze() {
  freeze_ = false;
}

WaitStateCode WaitStateInfo::get_frozen_state() const {
  if (frozen_state_code_ != WaitStateCode::Unused) {
    return frozen_state_code_;
  }
  return code_;
}

void WaitStateInfo::push_state(WaitStateCode c) {
  DCHECK(state_to_pop_to_.load() == WaitStateCode::Unused) << "We only support one level of push/pop";
  state_to_pop_to_ = code_.exchange(c, std::memory_order_acq_rel);
}

void WaitStateInfo::pop_state(WaitStateCode c) {
  DCHECK_EQ(code_.load(), c) << "We only support one level of push/pop. "
      << "Previous state was " << util::ToString(state_to_pop_to_.load());
  code_ = state_to_pop_to_.exchange(WaitStateCode::Unused, std::memory_order_acq_rel);
}

void WaitStateInfo::set_state_if(WaitStateCode prev, WaitStateCode c) {
  std::string old_to_string;
  if (VLOG_IS_ON(3)) {
    old_to_string = ToString();
  }
  if (!WaitsForSomething(c)) {
    c = WaitStateCode::ActiveOnCPU;
  }
  auto ret = code_.compare_exchange_strong(prev, c);
  if (!ret) {
    return;
  }
  check_and_update_thread_id(prev, c);
  VTRACE(0, "cas-ed $0 -> $1", util::ToString(prev), util::ToString(c));
  VLOG(3) << this << " " << old_to_string << " setting state to " << util::ToString(c);
  if (freeze_) {
    // See comments in ::set_state()
    if (frozen_state_code_ == WaitStateCode::Unused) {
      frozen_state_code_ = prev;
    }
  } else {
    frozen_state_code_ = WaitStateCode::Unused;
  }
  VLOG(3) << this << " " << ToString() << " setting state to " << util::ToString(c)
          << " if current state is " << util::ToString(prev);
#ifdef TRACK_WAIT_HISTORY
  {
    std::lock_guard<simple_spinlock> l(mutex_);
    history_.emplace_back(code_);
    // history_.push_back(code_);
  }
  num_updates_++;
#endif
}

void WaitStateInfo::set_state(WaitStateCode c) {
  TRACE(util::ToString(c));
  check_and_update_thread_id(code_.load(), c);
  VLOG(3) << this << " " << ToString() << " setting state to " << util::ToString(c);
  if (freeze_) {
    // If this is the first time we are calling set_state after freeze() was called,
    // we will save the current state before updating it.
    // If state is not frozen, then we reset frozen_state to Unused.
    //
    // This mechanism assumes that when unfreeze is called, the WaitState will be updated at least
    // once, before the next freeze is called. This should generally be fine as we plan to freeze
    // when AUHmetadata is pulled -- once every second, while the update should happen more often.
    // But just something to note. If this is violated, then the 2nd, frozen state will represent
    // the start of the 1st freeze window, instead of reflecting the start state of the 2nd freeze
    // window.
    if (frozen_state_code_ == WaitStateCode::Unused) {
      frozen_state_code_ = code_.load();
    }
  } else {
    frozen_state_code_ = WaitStateCode::Unused;
  }
  code_ = c;
  #ifdef TRACK_WAIT_HISTORY
  {
    std::lock_guard<simple_spinlock> l(mutex_);
    history_.emplace_back(code_);
    // history_.push_back(code_);
  }
  num_updates_++;
  #endif
}

WaitStateCode WaitStateInfo::get_state() const {
  if (GetAtomicFlag(&FLAGS_freeze_wait_states)) {
    return get_frozen_state();
  }
  return code_;
}

std::string WaitStateInfo::ToString() const {
  std::lock_guard<simple_spinlock> l(mutex_);
#ifdef TRACK_WAIT_HISTORY
  return YB_CLASS_TO_STRING(metadata, code, aux_info, num_updates, history);
#else
  return YB_CLASS_TO_STRING(metadata, code, aux_info);
#endif // TRACK_WAIT_HISTORY
}

WaitStateInfoPtr WaitStateInfo::CurrentWaitState() {
  return threadlocal_wait_state_;
}

void WaitStateInfo::set_current_request_id(int64_t current_request_id) {
  std::lock_guard<simple_spinlock> l(mutex_);
  metadata_.current_request_id = current_request_id;
}

void WaitStateInfo::set_top_level_request_id(uint64_t top_level_request_id) {
  std::lock_guard<simple_spinlock> l(mutex_);
  metadata_.top_level_request_id = {top_level_request_id, top_level_request_id * top_level_request_id};
}

void WaitStateInfo::set_query_id(int64_t query_id) {
  std::lock_guard<simple_spinlock> l(mutex_);
  metadata_.query_id = query_id;
}

void WaitStateInfo::set_client_node_ip(const std::string &endpoint) {
  std::lock_guard<simple_spinlock> l(mutex_);
  metadata_.set_client_node_ip(endpoint);
}

bool WaitsForLock(WaitStateCode c) {
  switch (c) {
    case WaitStateCode::LockedBatchEntry_Lock:
    case WaitStateCode::TransactionStatusCache_DoGetCommitData:
    case WaitStateCode::XreplCatalogManagerWaitForIsBootstrapRequired:
    case WaitStateCode::RpcsWaitOnMutexInShutdown:
    case WaitStateCode::MVCCWaitForSafeTime:
    case WaitStateCode::TxnCoordWaitForMutexInPrepareForDeletion:
    case WaitStateCode::PgResponseCache_Get:
    case WaitStateCode::BackfillIndexWaitForAFreeSlot:
    case WaitStateCode::YBCSyncLeaderMasterRpc:
    case WaitStateCode::YBCFindMasterProxy:
    case WaitStateCode::TxnResolveSealedStatus:
    case WaitStateCode::PgClientSessionStartExchange:
    case WaitStateCode::WaitForYsqlBackendsCatalogVersion:
     return true;
    default:
      return false;
  }
}

bool WaitsForIO(WaitStateCode c) {
  switch (c) {
    case WaitStateCode::ApplyingRaftEdits:
    case WaitStateCode::BlockCacheReadFromDisk:
    case WaitStateCode::CloseFile:
    case WaitStateCode::Handling:
    case WaitStateCode::WALLogSync:
    case WaitStateCode::CreatingNewTablet:
     return true;
    default:
      return false;
  }
}

bool WaitsForThread(WaitStateCode c) {
  switch (c) {
    case WaitStateCode::RaftWaitingForQuorum:
    case WaitStateCode::LookingUpTablet:
    case WaitStateCode::ResponseQueued:
    case WaitStateCode::Unused:
     return true;
    default:
      return false;
  }
}

bool WaitsForSomething(WaitStateCode c) {
  return WaitsForIO(c) || WaitsForLock(c) || WaitsForThread(c);
}

void WaitStateInfo::set_top_level_node_id(const std::vector<uint64_t> &top_level_node_id) {
  std::lock_guard<simple_spinlock> l(mutex_);
  metadata_.top_level_node_id = top_level_node_id;
}

void WaitStateInfo::UpdateMetadata(const AUHMetadata& meta) {
  std::lock_guard<simple_spinlock> l(mutex_);
  metadata_.UpdateFrom(meta);
}

void WaitStateInfo::UpdateAuxInfo(const AUHAuxInfo& aux) {
  std::lock_guard<simple_spinlock> l(mutex_);
  aux_info_.UpdateFrom(aux);
}

void WaitStateInfo::SetCurrentWaitState(WaitStateInfoPtr wait_state) {
  threadlocal_wait_state_ = wait_state;
}

#ifndef NDEBUG
simple_spinlock WaitStateInfo::does_io_lock_;
simple_spinlock WaitStateInfo::does_wait_lock_;
std::unordered_map<util::WaitStateCode, std::atomic_bool> WaitStateInfo::does_io(200);
std::unordered_map<util::WaitStateCode, std::atomic_bool> WaitStateInfo::does_wait(200);


void WaitStateInfo::AssertIOAllowed() {
  auto wait_state = CurrentWaitState();
  if (wait_state) {
    auto state = wait_state->get_state();
    bool inserted = false;
    {
      std::lock_guard<simple_spinlock> l(does_io_lock_);
      inserted = does_io.try_emplace(state, true).second;
    }
    LOG_IF(INFO, inserted) << wait_state->ToString() << " does_io Added " << util::ToString(state);
    // LOG_IF(INFO, inserted) << " at\n" << yb::GetStackTrace();
    DCHECK(WaitsForIO(state)) << "WaitForIO( " << util::ToString(state) << ") should be true";
  }
}

void WaitStateInfo::AssertWaitAllowed() {
  auto wait_state = CurrentWaitState();
  if (wait_state) {
    auto state = wait_state->get_state();
    bool inserted = false;
    {
      std::lock_guard<simple_spinlock> l(does_wait_lock_);
      inserted = does_wait.try_emplace(state, true).second;
    }
    LOG_IF(INFO, inserted) << wait_state->ToString() << " does_wait Added " << util::ToString(state);
    // LOG_IF(INFO, inserted) << " at\n" << yb::GetStackTrace();
    DCHECK(WaitsForLock(state)) << "WaitsForLock( " << util::ToString(state) << ") should be true";
  }
}

// Isn't really using locks to update thread_name_. But I think that's ok.
void WaitStateInfo::check_and_update_thread_id(WaitStateCode prev, WaitStateCode next) {
  auto tid = Thread::CurrentThreadId();
  if (tid != thread_id_.load()) {
    auto* current_thread = Thread::current_thread();
    if (!current_thread) {
      LOG(ERROR) << "Current thread is not a thread. Thread::current_thread() returns NULL";
    }
    auto cur_thread_name = current_thread->name();
    VLOG(1) << "Setting state to " << util::ToString(next) << " on " << cur_thread_name
            << " was previously " << util::ToString(prev) << " set on " << thread_name_;
    thread_name_ = std::move(cur_thread_name);
    thread_id_ = tid;
    DCHECK(WaitsForThread(prev)) << "WaitsForThread( " << util::ToString(prev) << ") should be true";
  }
}

#else
void WaitStateInfo::AssertIOAllowed() {}

void WaitStateInfo::AssertWaitAllowed() {}

void WaitStateInfo::check_and_update_thread_id(WaitStateCode prev, WaitStateCode next) {}
#endif // NDEBUG

ScopedWaitState::ScopedWaitState(WaitStateInfoPtr wait_state) {
  prev_state_ = WaitStateInfo::CurrentWaitState();
  WaitStateInfo::SetCurrentWaitState(wait_state);
}

ScopedWaitState::~ScopedWaitState() {
  WaitStateInfo::SetCurrentWaitState(prev_state_);
}

ScopedWaitStatus::ScopedWaitStatus(WaitStateInfoPtr wait_state, WaitStateCode state)
    : wait_state_(wait_state), state_(state) {
  if (wait_state_) {
    prev_state_ = wait_state_->get_state();
    wait_state_->set_state(state_);
  }
}

ScopedWaitStatus::ScopedWaitStatus(WaitStateCode state)
    : wait_state_(WaitStateInfo::CurrentWaitState()), state_(state) {
  if (wait_state_) {
    prev_state_ = wait_state_->get_state();
    wait_state_->set_state(state_);
  }
}

ScopedWaitStatus::~ScopedWaitStatus() {
  ResetToPrevStatus();
}

void ScopedWaitStatus::ResetToPrevStatus() {
  if (wait_state_ && wait_state_->get_state() == state_) {
    wait_state_->set_state(prev_state_);
  }
}

// Link to source codes for the classes below
// https://github.com/open-telemetry/opentelemetry-cpp/blob/main/sdk/src/common/random.cc
// https://github.com/open-telemetry/opentelemetry-cpp/blob/main/sdk/src/common/platform/fork_unix.cc
namespace platform
{
int AtFork(void (*prepare)(), void (*parent)(), void (*child)()) noexcept
{
  return ::pthread_atfork(prepare, parent, child);
}
}

class TlsRandomNumberGenerator
{
public:
  TlsRandomNumberGenerator() noexcept
  {
    Seed();
    platform::AtFork(nullptr, nullptr, OnFork);
  }

  static FastRandomNumberGenerator &engine() noexcept { return engine_; }

private:
  static thread_local FastRandomNumberGenerator engine_;

  static void OnFork() noexcept { Seed(); }

  static void Seed() noexcept
  {
    std::random_device random_device;
    std::seed_seq seed_seq{random_device(), random_device(), random_device(), random_device()};
    engine_.seed(seed_seq);
  }
};

thread_local FastRandomNumberGenerator TlsRandomNumberGenerator::engine_{};

FastRandomNumberGenerator &AUHRandom::GetRandomNumberGenerator() noexcept
{
  static thread_local TlsRandomNumberGenerator random_number_generator{};
  return TlsRandomNumberGenerator::engine();
}

uint64_t AUHRandom::GenerateRandom64() noexcept
{
  return GetRandomNumberGenerator()();
}

}