package com.visionrent.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Function;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.visionrent.domain.Car;
import com.visionrent.domain.Reservation;
import com.visionrent.domain.User;
import com.visionrent.domain.enums.ReservationStatus;
import com.visionrent.dto.ReservationDTO;
import com.visionrent.dto.request.ReservationRequest;
import com.visionrent.dto.request.ReservationUpdateRequest;
import com.visionrent.exception.BadRequestException;
import com.visionrent.exception.ResourceNotFoundException;
import com.visionrent.exception.message.ErrorMessage;
import com.visionrent.mapper.ReservationMapper;
import com.visionrent.repository.ReservationRepository;

@Service
public class ReservationService {
	
	@Autowired
	private ReservationRepository reservationRepository ;
	@Autowired
	private ReservationMapper reservationMapper;

	
	// ************************ CREATE ***************
	
	public void createReservation(ReservationRequest reservationRequest, User user, Car car) {
		
		checkReservationTimeIsCorrect(reservationRequest.getPickUpTime(), reservationRequest.getDropOffTime());
		
		boolean carStatus = checkCarAvailability(car, reservationRequest.getPickUpTime(), reservationRequest.getDropOffTime());
		
		  Reservation reservation = reservationMapper.reservationRequestToReservation(reservationRequest);
		  
		  if(carStatus) {
			  reservation.setStatus(ReservationStatus.CREATED);
		  } else {
			  throw new BadRequestException(ErrorMessage.CAR_NOT_AVAILABLE_MESSAGE);
		  }
		  
		  reservation.setCar(car);
		  reservation.setUser(user);
		  
		    Double totalPrice  = getTotalPrice(car, reservationRequest.getPickUpTime(), reservationRequest.getDropOffTime());
		    
		    reservation.setTotalPrice(totalPrice);
		    
		    reservationRepository.save(reservation);
		  
		  
		   
		
	}
	
	//-----> 
	public void checkReservationTimeIsCorrect(LocalDateTime pickUpTime, LocalDateTime dropOffTime) {
		
		LocalDateTime now  = LocalDateTime.now();
		
		if(pickUpTime.isBefore(now)) { // rezervasyon ba??lang???? tarihi bug??nden ??nceki
			throw new BadRequestException(ErrorMessage.RESERVATION_TIME_INCORRECT_MESSAGE);
		}
		
		 boolean isEqual = pickUpTime.isEqual(dropOffTime)?true:false;// ba??. tarihi  ve bt?? tarihi e??it mi?
		 boolean isBefore = pickUpTime.isBefore(dropOffTime)?true:false; // ba??. tarihi , biti?? tarihinden ??nce mi 
		 
		 if(isEqual || !isBefore) {
			 throw new BadRequestException(ErrorMessage.RESERVATION_TIME_INCORRECT_MESSAGE);
			 
		 }
	}
	
	//-------->
	public boolean checkCarAvailability(Car car,LocalDateTime pickUpTime, LocalDateTime dropOffTime) {
		
		List<Reservation> existReservations = getConflictReservations(car, pickUpTime, dropOffTime);
		 
		 return existReservations.isEmpty();
		
	}
	
	//------->
	public Double getTotalPrice(Car car,LocalDateTime pickUpTime, LocalDateTime dropOffTime) {
		
		//Java.time dan gelen ChronoUnit --> 2 ayr?? zaman aras??ndaki dakikay?? al??yoruz
		Long minutes = ChronoUnit.MINUTES.between(pickUpTime, dropOffTime);
		double hours = Math.ceil(minutes/60.0); // Math.ceil metodu say??y?? yukar?? yuvarl??yor
		return car.getPricePerHour()*hours;
		
	}
	
	//------>
	private List<Reservation> getConflictReservations(Car car,LocalDateTime pickUpTime, LocalDateTime dropOffTime) {
		
		if(pickUpTime.isAfter(dropOffTime)) {
			throw new BadRequestException(ErrorMessage.RESERVATION_TIME_INCORRECT_MESSAGE);
		}
		
		ReservationStatus[] status = {ReservationStatus.CANCELED, ReservationStatus.DONE};
		
		     List<Reservation> existReservations =  reservationRepository.checkCarStatus(car.getId(), pickUpTime, dropOffTime, status);
		     
		     return existReservations;
		
		
		
	}

	//**********************************************************
	
	public List<ReservationDTO> getAllReservations() {

		 List<Reservation> reservations = reservationRepository.findAll();
		 
		 return reservationMapper.map(reservations);
		 
		
	}
	

	//********************************************

	public Page<ReservationDTO> getReservationPage(Pageable pageable) {
		
		 Page<Reservation> reservationPage  = reservationRepository.findAll(pageable);
		 // Page<ReservationDTO> reservationPageDTO = reservationPage.map(reservationMapper::reservationToReservationDTO);
		 
		 return getReservationDTOPage(reservationPage);
		 
	}
	
	private Page<ReservationDTO> getReservationDTOPage(Page<Reservation> reservationPage){
		
	 Page<ReservationDTO> reservationDTOPage	= reservationPage.map(new Function<Reservation, ReservationDTO>() {
			@Override
			public ReservationDTO apply(Reservation reservation) {
				
				return reservationMapper.reservationToReservationDTO(reservation);
			}
			});
		
		return reservationDTOPage;
	}
	
	//*****************************************************************
	

	public void updateReservation(Long reservationId, Car car, ReservationUpdateRequest reservationUpdateRequest) {
		
		Reservation reservation = getById(reservationId);
		
		// rezervasyon stat??s?? cancel veya done ise , update yap??lam??yacak
		if(reservation.getStatus().equals(ReservationStatus.CANCELED) || reservation.getStatus().equals(ReservationStatus.DONE) ) {
			throw new BadRequestException(ErrorMessage.RESERVATION_STATUS_CANT_CHANGE_MESSAGE);
		}
		// e??er reservation Cancel veya Done olacaksa pickUpTime ve dropOffTime kontrol?? yap??lmas??n,
		// ama sadece Create yaparken zaman kontrol?? yap??ls??n
		if(reservationUpdateRequest.getStatus()!=null && 
			reservationUpdateRequest.getStatus()== ReservationStatus.CREATED) {
			checkReservationTimeIsCorrect(reservationUpdateRequest.getPickUpTime(), reservationUpdateRequest.getDropOffTime());
		//  conflict kontrolu , de??i??tirilmek istenen tarih aral??????nda asrac??n ba??ka bir rtezervasyonu varsa
			  List<Reservation> conflictReservations =  getConflictReservations(car, reservationUpdateRequest.getPickUpTime(), 
					   																										reservationUpdateRequest.getDropOffTime());
			  if(!conflictReservations.isEmpty()) {  //***********************************  !!!!!!!!!!!!!!!!!!!!!!!!!!!  **************
					if(!(conflictReservations.size()==1 && conflictReservations.get(0).getId().equals(reservationId))) {
						throw new BadRequestException(ErrorMessage.CAR_NOT_AVAILABLE_MESSAGE);
					}
				}
			  
			  // fiyat hesaplamas??
			  Double totalPrice =getTotalPrice(car, reservationUpdateRequest.getPickUpTime(), reservationUpdateRequest.getDropOffTime());
			  // TODO bak??lacak
			  reservation.setTotalPrice(totalPrice);
			  reservation.setCar(car);
			
		}
		
		reservation.setPickUpTime(reservationUpdateRequest.getPickUpTime());
		reservation.setDropOffTime(reservationUpdateRequest.getDropOffTime());
		reservation.setPickUpLocation(reservationUpdateRequest.getPickUpLocation());
		reservation.setDropOffLocation(reservationUpdateRequest.getDropOffLocation());
		reservation.setStatus(reservationUpdateRequest.getStatus());
		
		reservationRepository.save(reservation);

	}
	
	public Reservation getById(Long id)  {
		Reservation reservation = reservationRepository.findById(id).orElseThrow(()->new 
				ResourceNotFoundException(String.format(ErrorMessage.RESOURCE_NOT_FOUND_MESSAGE, id)));
		
		return reservation ;
		
	}
	
	//******************************************************************************************************

	public ReservationDTO getReservationDTO(Long id) {
		Reservation reservation = getById(id);
		return reservationMapper.reservationToReservationDTO(reservation);
	}
//*****************************************************

	public Page<ReservationDTO> findReservationPageByUser(User user, Pageable pageable) {
		 Page<Reservation> reservationPage = reservationRepository.findAllByUser(user, pageable);
		 
		 return getReservationDTOPage(reservationPage);
	}
	//************************************************************

	public ReservationDTO findByIdAndUser(Long id, User user) {
		Reservation reservation =  reservationRepository.findByIdAndUser(id, user).orElseThrow(()->new ResourceNotFoundException(
				 String.format(ErrorMessage.RESOURCE_NOT_FOUND_MESSAGE, id)));
		
		return reservationMapper.reservationToReservationDTO(reservation);
	}

	//***************REMOVE ***************************
	
	public void removeById(Long id) {
		
			boolean exist  =  reservationRepository.existsById(id);
			
			if(!exist ) {
				throw new ResourceNotFoundException(String.format(ErrorMessage.RESOURCE_NOT_FOUND_MESSAGE, id));
				
			}
			
			
			reservationRepository.deleteById(id);
		
	}

	public boolean existsByCar(Car car) {
		
		return reservationRepository.existsByCar(car);
	}

	public boolean existsByUser(User user) {
		return reservationRepository.existsByUser(user);
	}

	public List<Reservation> getAll() {
		
		return reservationRepository.findAllBy();
	}


	
	
	
	
	

}
