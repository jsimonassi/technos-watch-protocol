// TurboModule mínimo em Objective-C++, só para o compilador conferir a API do framework como o shim do iOS a verá.
//
// Não roda e não liga: a tarefa `./gradlew :bridge:checkObjcInterop` faz `clang++ -fsyntax-only` deste arquivo contra o
// framework guarda-chuva de debug do simulador. É a forma do `RCTNativeCoreNext.mm` do Repo 2: cada método do spec
// (`src/specs/NativeCoreNext.ts`) embrulha o par resolve/reject do React Native num `TWPCoreCompletion` e chama o método
// de mesmo nome do `TWPCoreBridge`; o módulo é o `TWPCoreEventSink` e converte cada payload com o
// `TWPFoundationConverter` antes do `emitOnX:`. Os tipos do React Native são copiados aqui (o check não tem o RN).
// O que ele cobre está no README.md ao lado.

#import <Foundation/Foundation.h>
#import <TechnosWatchProtocol/TechnosWatchProtocol.h>

#include <array>
#include <functional>
#include <string>
#include <string_view>

NS_ASSUME_NONNULL_BEGIN

// Os mesmos tipos de React/RCTBridgeModule.h.
typedef void (^ShimPromiseResolveBlock)(id _Nullable result);
typedef void (^ShimPromiseRejectBlock)(NSString *code, NSString *_Nullable message, NSError *_Nullable error);

// Os nomes de `CoreEventSink.emit`; um nome fora da lista é erro do controlador, não evento perdido em silêncio.
static constexpr std::array<std::string_view, 13> kEventNames = {
	"onScannedDevice", "onConnectStateChanged", "onDeviceInfo", "onFirmwareInfo", "onBatteryStatus", "onPedometer",
	"onHistoryPedometer", "onHistorySleep", "onSyncStarted", "onSyncProgress", "onSyncFinished", "onError", "onLog",
};

static bool ShimIsKnownEvent(NSString *name)
{
	const std::string_view value([name UTF8String]);
	for (const auto &known : kEventNames) {
		if (known == value) {
			return true;
		}
	}
	return false;
}

/// O par resolve/reject do React Native como `CoreCompletion`. O valor passa inteiro pelo `FoundationConverter` numa
/// lista de um elemento: o `KotlinBoolean` do `isConnected` vira `CFBoolean` (`true`/`false` no JS), e os mapas e
/// listas, `NSDictionary`/`NSArray` de verdade.
@interface ShimCompletion : NSObject <TWPCoreCompletion>
- (instancetype)initWithResolve:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject;
@end

@implementation ShimCompletion {
	ShimPromiseResolveBlock _resolve;
	ShimPromiseRejectBlock _reject;
}

- (instancetype)initWithResolve:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	if ((self = [super init])) {
		_resolve = [resolve copy];
		_reject = [reject copy];
	}
	return self;
}

- (void)resolveValue:(id _Nullable)value
{
	if (value == nil) {
		_resolve(nil);
		return;
	}
	NSArray *converted = [TWPFoundationConverter.shared toArrayValues:@[ value ]];
	_resolve(converted.firstObject);
}

- (void)rejectCode:(NSString *)code message:(NSString *)message
{
	_reject(code, message, nil);
}

@end

/// O que o codegen do RN 0.81 gera no `NativeCoreNextSpecBase` (`GenerateModuleObjCpp/index.js`): o callback de eventos
/// num ivar protegido, que o RN preenche pelo `setEventEmitterCallback:` (fora daqui: o wrapper é tipo do RN), e um
/// `emitOnX:` por evento (no codegen, cada um chama esse callback; aqui ficam vazios).
@interface ShimSpecBase : NSObject {
@protected
	// O mesmo tipo de `facebook::react::EventEmitterCallback` (`ReactCommon/RCTTurboModule.h`).
	std::function<void(const std::string &, id)> _eventEmitterCallback;
}
- (void)emitOnScannedDevice:(NSDictionary *)value;
- (void)emitOnConnectStateChanged:(NSDictionary *)value;
- (void)emitOnDeviceInfo:(NSDictionary *)value;
- (void)emitOnFirmwareInfo:(NSDictionary *)value;
- (void)emitOnBatteryStatus:(NSDictionary *)value;
- (void)emitOnPedometer:(NSDictionary *)value;
- (void)emitOnHistoryPedometer:(NSDictionary *)value;
- (void)emitOnHistorySleep:(NSDictionary *)value;
- (void)emitOnSyncStarted:(NSDictionary *)value;
- (void)emitOnSyncProgress:(NSDictionary *)value;
- (void)emitOnSyncFinished:(NSDictionary *)value;
- (void)emitOnError:(NSDictionary *)value;
- (void)emitOnLog:(NSDictionary *)value;
@end

@implementation ShimSpecBase
- (void)emitOnScannedDevice:(NSDictionary *)value {}
- (void)emitOnConnectStateChanged:(NSDictionary *)value {}
- (void)emitOnDeviceInfo:(NSDictionary *)value {}
- (void)emitOnFirmwareInfo:(NSDictionary *)value {}
- (void)emitOnBatteryStatus:(NSDictionary *)value {}
- (void)emitOnPedometer:(NSDictionary *)value {}
- (void)emitOnHistoryPedometer:(NSDictionary *)value {}
- (void)emitOnHistorySleep:(NSDictionary *)value {}
- (void)emitOnSyncStarted:(NSDictionary *)value {}
- (void)emitOnSyncProgress:(NSDictionary *)value {}
- (void)emitOnSyncFinished:(NSDictionary *)value {}
- (void)emitOnError:(NSDictionary *)value {}
- (void)emitOnLog:(NSDictionary *)value {}
@end

/// O TurboModule: casca sobre o `TWPCoreBridge` do processo (que o `CoreGraph.swift` monta).
@interface ShimNativeCoreNext : ShimSpecBase <TWPCoreEventSink>
- (instancetype)initWithBridge:(TWPCoreBridge *)bridge;
@end

@implementation ShimNativeCoreNext {
	TWPCoreBridge *_bridge;
	BOOL _attached;
}

- (instancetype)initWithBridge:(TWPCoreBridge *)bridge
{
	if ((self = [super init])) {
		_bridge = bridge;
	}
	return self;
}

/// Como o `graph()` do Android: o módulo se liga na primeira chamada de um método JS.
- (TWPCoreBridge *)attachedBridge
{
	if (!_attached) {
		_attached = YES;
		[_bridge attachSink:self];
	}
	return _bridge;
}

static id<TWPCoreCompletion> ShimCompletionOf(ShimPromiseResolveBlock resolve, ShimPromiseRejectBlock reject)
{
	return [[ShimCompletion alloc] initWithResolve:resolve reject:reject];
}

#pragma mark - Ciclo de vida

- (void)invalidate
{
	if (_attached) {
		[_bridge invalidateSink:self];
		_attached = NO;
	}
}

- (void)appWillEnterForeground
{
	if (_attached) {
		[_bridge onHostResume];
	}
}

#pragma mark - TWPCoreEventSink

- (BOOL)canEmit
{
	return static_cast<bool>(_eventEmitterCallback);
}

- (void)emitName:(NSString *)name payload:(NSDictionary<NSString *, id> *)payload
{
	NSAssert(ShimIsKnownEvent(name), @"Unknown event %@", name);
	NSDictionary *value = [TWPFoundationConverter.shared toDictionaryPayload:payload];
	if ([name isEqualToString:@"onScannedDevice"]) {
		[self emitOnScannedDevice:value];
	} else if ([name isEqualToString:@"onConnectStateChanged"]) {
		[self emitOnConnectStateChanged:value];
	} else if ([name isEqualToString:@"onDeviceInfo"]) {
		[self emitOnDeviceInfo:value];
	} else if ([name isEqualToString:@"onFirmwareInfo"]) {
		[self emitOnFirmwareInfo:value];
	} else if ([name isEqualToString:@"onBatteryStatus"]) {
		[self emitOnBatteryStatus:value];
	} else if ([name isEqualToString:@"onPedometer"]) {
		[self emitOnPedometer:value];
	} else if ([name isEqualToString:@"onHistoryPedometer"]) {
		[self emitOnHistoryPedometer:value];
	} else if ([name isEqualToString:@"onHistorySleep"]) {
		[self emitOnHistorySleep:value];
	} else if ([name isEqualToString:@"onSyncStarted"]) {
		[self emitOnSyncStarted:value];
	} else if ([name isEqualToString:@"onSyncProgress"]) {
		[self emitOnSyncProgress:value];
	} else if ([name isEqualToString:@"onSyncFinished"]) {
		[self emitOnSyncFinished:value];
	} else if ([name isEqualToString:@"onError"]) {
		[self emitOnError:value];
	} else if ([name isEqualToString:@"onLog"]) {
		[self emitOnLog:value];
	}
}

#pragma mark - Métodos do spec (NativeCoreNextSpec)

- (void)initialize:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] initializeCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)initSMASDK:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] doInitSMASDKCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)scan
{
	[[self attachedBridge] scan];
}

- (void)stopScan
{
	[[self attachedBridge] stopScan];
}

- (void)pairDevice:(NSString *)deviceId name:(NSString *)name resolve:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] pairDeviceDeviceId:deviceId name:name completion:ShimCompletionOf(resolve, reject)];
}

- (void)startPairingAttempt:(NSString *)deviceId name:(NSString *)name resolve:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] startPairingAttemptDeviceId:deviceId name:name completion:ShimCompletionOf(resolve, reject)];
}

- (void)disconnectCurrentDevice:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] disconnectCurrentDeviceCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)forgetDevice:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] forgetDeviceCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)isConnected:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] isConnectedCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)getLastPairedDevice:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] getLastPairedDeviceCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)getDeviceInfo:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] getDeviceInfoCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)getFirmwareInfo:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] getFirmwareInfoCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)getBatteryStatus:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] getBatteryStatusCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)setTime:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] setTimeCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)refreshSteps:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] refreshStepsCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)syncDataHistory:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] syncDataHistoryCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)getHistoryPedometer:(double)daysBefore resolve:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] getHistoryPedometerDaysBefore:daysBefore completion:ShimCompletionOf(resolve, reject)];
}

- (void)getHistorySleep:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] getHistorySleepCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)deletedSavedData:(NSArray *)filteredArrayData category:(double)category
{
	[[self attachedBridge] deletedSavedDataFilteredArrayData:filteredArrayData category:category];
}

- (void)getLogs:(double)limit resolve:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] getLogsLimit:limit completion:ShimCompletionOf(resolve, reject)];
}

- (void)exportLogs:(ShimPromiseResolveBlock)resolve reject:(ShimPromiseRejectBlock)reject
{
	[[self attachedBridge] exportLogsCompletion:ShimCompletionOf(resolve, reject)];
}

- (void)clearLogs
{
	[[self attachedBridge] clearLogs];
}

- (void)setLogLevel:(double)level
{
	[[self attachedBridge] setLogLevelLevel:level];
}

@end

NS_ASSUME_NONNULL_END
